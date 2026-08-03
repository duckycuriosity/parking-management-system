"""
Bo mo phong MQTT cho toan bo 43 testcase unhappy/edge case cua nghiep vu matching
face+plate (xem file Testcase_UnhappyCase_VanHanh_MatchingFacePlate_30-07-2026.xlsx).
Publish dung JSON schema "rich" ma AI service tra ve (eventId/cameraUUID/eventTime/
images.../face.quality... hoac plate.confidence...).

Yeu cau trong DB — 4 camera UUID mac dinh duoi day phai duoc gan dung barrier_camera:
    entry_face_camera  = 8f660a77-49e2-4f5d-b113-1de0043a2a5b   (barrier ENTRY, role=face)
    entry_plate_camera = d6efe615-46df-4ce9-966f-32138550ab54   (barrier ENTRY, role=plate)
    exit_face_camera   = 88fc72c5-2e76-412b-b303-e972bc43c2bb   (barrier EXIT,  role=face)
    exit_plate_camera  = 599747d5-20c7-4819-b210-e4e3857462b1   (barrier EXIT,  role=plate)
Ca 2 barrier (ENTRY+EXIT) nen thuoc cung 1 parking_device (mo phong dung topology
thuc te: 1 Pi = 2 barrier, moi barrier 2 camera).

Cai dat:
    pip install paho-mqtt

CHAY 1 TESTCASE CU THE (vi du TC-004):
    python simulate_vehicle_entry.py --tc4

LIET KE TOAN BO TESTCASE:
    python simulate_vehicle_entry.py --list

CHAY TUAN TU CA 43 TESTCASE (rat lau do nhieu buoc cho TTL/business-window):
    python simulate_vehicle_entry.py --tc-all --yes

CHAY KICH BAN "GENERIC" (khong dung --tcN, hanh vi cu, tu chinh tay bang flags):
    python simulate_vehicle_entry.py --plate "51A-111.11"
    python simulate_vehicle_entry.py --plate "51A-111.11" --face-quality bad
    python simulate_vehicle_entry.py --plate "51A-111.11" --plate-burst 0

Luu y quan trong:
    - Mot so testcase (nhom E ha tang, TC-034/035/037; field test nhom F, TC-016/038)
      KHONG the mo phong day du chi bang message MQTT (can can thiep vat ly/ha tang/
      DB). Cac testcase nay se in ra huong dan thao tac thu cong ([CAN THAO TAC THU CONG])
      thay vi gia vo mo phong duoc.
    - Mot so testcase (TC-007, TC-020) can UUID camera "loi cau hinh" rieng, khong co
      gia tri that mac dinh — phai truyen qua --unassigned-plate-camera-uuid /
      --misconfigured-plate-camera-uuid / --misconfigured-face-camera-uuid tro dung
      camera trong DB test cua ban, neu khong ket qua se giong TC-008 (camera la hoan
      toan) thay vi dung kich ban "camera hop le nhung thieu mapping".
"""

import argparse
import json
import os
import random
import threading
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

CONFUSABLE_CHARS = {"0": "O", "O": "0", "1": "I", "I": "1", "8": "B", "B": "8", "5": "S", "S": "5"}

QUALITY_PRESETS = {
    "good": {
        "score": 0.85, "usable": True, "issues": [],
        "detScore": 0.9, "blurScore": 60.0, "brightness": 120,
        "yawDeg": 5.0, "pitchDeg": -3.0,
    },
    "bad": {
        "score": 0.42, "usable": False, "issues": ["blurry", "face_turned_away", "face_tilted"],
        "detScore": 0.5, "blurScore": 18.0, "brightness": 90,
        "yawDeg": 60.0, "pitchDeg": -50.0,
    },
}


# =====================================================================
# Builders JSON payload (dung chung cho moi testcase)
# =====================================================================

def mutate_plate(plate: str) -> str:
    """Gia lap OCR doc nham 1 ky tu de mo phong burst message hoi khac nhau."""
    chars = list(plate)
    candidates = [i for i, c in enumerate(chars) if c in CONFUSABLE_CHARS]
    if not candidates:
        return plate
    idx = random.choice(candidates)
    chars[idx] = CONFUSABLE_CHARS[chars[idx]]
    return "".join(chars)


def generate_plate_burst(base_plate: str, count: int):
    """Tra ve list (plateNumber, confidence) mo phong 1 burst OCR cho cung 1 xe.
    Bien so 'dung' (base_plate) luon co confidence cao nhat trong burst, cac
    bien so bi doc nham co confidence thap hon, thu tu den bi xao tron."""
    if count <= 0:
        return []

    variants = []
    confidences = sorted(round(random.uniform(0.55, 0.85), 3) for _ in range(max(count - 1, 0)))
    for conf in confidences:
        variants.append((mutate_plate(base_plate), conf))
    variants.append((base_plate, round(random.uniform(0.9, 0.99), 3)))
    random.shuffle(variants)
    return variants


def random_plate(prefix: str = "51X") -> str:
    """Sinh 1 bien so ngau nhien de tranh dung do giua cac lan chay / cac testcase."""
    return f"{prefix}-{random.randint(100, 999)}.{random.randint(10, 99)}"


def _image_entry(base_name: str, folder: str, suffix: str):
    file_name = f"{base_name}_{suffix}.jpg"
    return {
        "fileName": file_name,
        "storagePath": f"{folder}/{file_name}",
        "accessUrl": f"http://0.0.0.0:5000/public/media/{folder}/{file_name}?exp=0&sig=simulated",
    }


def _drop(d: dict, path: str):
    """Xoa 1 key long nhau khoi dict theo duong dan 'a.b.c' — dung de mo phong AI
    ban payload thieu field (vd 'face.quality.usable', 'face.profileId', 'images')."""
    parts = path.split(".")
    cur = d
    for p in parts[:-1]:
        if not isinstance(cur, dict) or p not in cur:
            return
        cur = cur[p]
    if isinstance(cur, dict):
        cur.pop(parts[-1], None)


def build_plate_event(camera_uuid: str, plate_number: str, confidence: float, event_id: int):
    now = datetime.now(timezone.utc)
    base_name = f"sim_{int(now.timestamp() * 1000)}"
    return {
        "eventId": event_id,
        "eventType": "license_plate_recognition",
        "modelName": "plate_detection_ocr",
        "cameraUUID": camera_uuid,
        "eventTime": now.isoformat(),
        "images": {
            "fullFrame": _image_entry(base_name, "snapshots", "full"),
            "objectCrop": _image_entry(base_name, "objects", "vehicle"),
            "plateCrop": _image_entry(base_name, "plates", "plate"),
            "deblurPlateCrop": _image_entry(base_name, "plates", "plate-deblur"),
        },
        "object": {
            "trackId": 0,
            "label": 2,
            "labelName": "car",
            "cropBox": [0.36875, 0.294444, 0.885156, 1],
        },
        "plate": {
            "plateNumber": plate_number,
            "confidence": confidence,
            "isVerified": False,
        },
        "vehicle": {
            "typeVehicle": "other",
            "vehicleTypeConfidence": 0.9,
        },
    }


def build_face_event(camera_uuid: str, profile_id, confidence: float, quality_preset: str, event_id: int,
                      quality_overrides: dict = None):
    now = datetime.now(timezone.utc)
    base_name = f"sim_{int(now.timestamp() * 1000)}"
    quality = dict(QUALITY_PRESETS[quality_preset])
    if quality_overrides:
        quality.update(quality_overrides)
    return {
        "eventId": event_id,
        "eventType": "face_recognition",
        "modelName": "face_recognition",
        "cameraUUID": camera_uuid,
        "eventTime": now.isoformat(),
        "images": {
            "fullFrame": _image_entry(base_name, "snapshots", "full"),
            "objectCrop": _image_entry(base_name, "objects", "person"),
            "faceCrop": _image_entry(base_name, "faces", "face"),
        },
        "object": {
            "trackId": random.randint(1, 999),
            "label": "person",
            "cropBox": [0.196875, 0.5125, 0.285156, 0.763542],
        },
        "face": {
            "profileId": profile_id,
            "profileName": "Unknown" if profile_id is None else f"customer_{profile_id}",
            "profileUUID": None,
            "isVerified": profile_id is not None,
            "confidence": confidence,
            "threshold": 0.38,
            "matchType": "matched" if profile_id is not None else "unknown",
            "quality": quality,
        },
    }


# =====================================================================
# MQTT client
# =====================================================================

def create_client(args) -> mqtt.Client:
    try:
        client = mqtt.Client(
            client_id=args.client_id,
            protocol=mqtt.MQTTv311,
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        )
    except AttributeError:
        # paho-mqtt < 2.0 khong co CallbackAPIVersion
        client = mqtt.Client(client_id=args.client_id, protocol=mqtt.MQTTv311)

    if args.username:
        client.username_pw_set(args.username, args.password)

    def on_connect(client_, userdata, flags, reason_code, properties=None):
        print(f"[mqtt] connected: {reason_code}")

    def on_publish(client_, userdata, mid, reason_code=None, properties=None):
        print(f"[mqtt] published mid={mid}")

    client.on_connect = on_connect
    client.on_publish = on_publish
    return client


def publish_json(client: mqtt.Client, topic: str, payload: dict):
    client.publish(topic, json.dumps(payload, ensure_ascii=False), qos=1)


# =====================================================================
# Helper mo phong dung cho tung testcase (in log ro rang, de doi chieu ket qua)
# =====================================================================

def make_seq():
    return random.randint(1, 100000)


def pick_profile_id(args):
    return args.profile_id if args.profile_id is not None else random.randint(1000, 9000)


def send_plate(client, args, camera_uuid, plate, confidence, seq, drop_paths=None, note=""):
    seq += 1
    payload = build_plate_event(camera_uuid, plate, confidence, seq)
    for p in (drop_paths or []):
        _drop(payload, p)
    publish_json(client, args.plate_topic, payload)
    print(f"  [plate] plate={plate!r} confidence={confidence} camera={camera_uuid} {note}".rstrip())
    return seq


def send_face(client, args, camera_uuid, profile_id, confidence, quality, seq,
              drop_paths=None, quality_overrides=None, note=""):
    seq += 1
    payload = build_face_event(camera_uuid, profile_id, confidence, quality, seq,
                                quality_overrides=quality_overrides)
    for p in (drop_paths or []):
        _drop(payload, p)
    publish_json(client, args.face_topic, payload)
    print(f"  [face] profileId={profile_id} confidence={confidence} quality={quality} "
          f"camera={camera_uuid} {note}".rstrip())
    return seq


def wait(seconds, why):
    print(f"--- Cho {seconds}s ({why}) ---")
    time.sleep(seconds)


def note(msg):
    print(f"    >> {msg}")


def expect(msg):
    print(f"    == KET QUA MONG DOI: {msg}")


def precond(msg):
    print(f"    ?? [DIEU KIEN DAU VAO CAN CHUAN BI TRUOC] {msg}")


def warn_manual(msg):
    print(f"    !! [CAN THAO TAC THU CONG / KHONG MO PHONG DUOC QUA MQTT] {msg}")


# =====================================================================
# Kich ban "generic" cu (giu nguyen hanh vi khi khong dung --tcN nao)
# =====================================================================

def simulate_vehicle_entry(client, args):
    event_id_seq = random.randint(1, 100000)

    burst = generate_plate_burst(args.plate, args.plate_burst)
    print(f"--- Bat dau burst {len(burst)} message plate cho bien so goc '{args.plate}' ---")
    for i, (plate, confidence) in enumerate(burst, start=1):
        event_id_seq += 1
        payload = build_plate_event(args.entry_plate_camera_uuid, plate, confidence, event_id_seq)
        publish_json(client, args.plate_topic, payload)
        print(f"  [plate {i}/{len(burst)}] plateNumber={plate!r} confidence={confidence}")
        time.sleep(args.plate_interval)

    print(f"--- Cho {args.face_delay}s (mo phong nguoi lai di toi camera face) ---")
    time.sleep(args.face_delay)

    event_id_seq += 1
    face_payload = build_face_event(
        args.entry_face_camera_uuid, args.profile_id, args.face_confidence, args.face_quality, event_id_seq,
    )
    publish_json(client, args.face_topic, face_payload)
    print(f"--- Da gui face: profileId={args.profile_id} confidence={args.face_confidence} "
          f"quality={args.face_quality} ---")

    if args.plate_burst > 0:
        if args.face_delay > args.session_ttl_seconds:
            print(f"--- Ket qua mong doi: face-delay ({args.face_delay}s) > session-ttl-seconds "
                  f"({args.session_ttl_seconds}s) => plate session da het han truoc khi face toi. "
                  f"ingestFace se KHONG tim thay session -> bo qua, KHONG tao parking_session, "
                  f"KHONG mo barrier. (TC-004) ---")
        else:
            print(f"--- Ket qua mong doi: face-delay ({args.face_delay}s) <= session-ttl-seconds "
                  f"({args.session_ttl_seconds}s) => plate session con hieu luc, neu face hop le se "
                  f"duoc match binh thuong (happy case). ---")


# =====================================================================
# Registry testcase
# =====================================================================

TESTCASES = {}


def testcase(tc_id, code, title):
    def deco(fn):
        TESTCASES[tc_id] = (code, title, fn)
        return fn
    return deco


def list_testcases():
    print("Danh sach testcase mo phong duoc (dung --tcN de chay, vi du --tc4 = TC-004):\n")
    for i in sorted(TESTCASES):
        code, title, _ = TESTCASES[i]
        print(f"  --tc{i:<3} {code}  {title}")


# --- A. Tin hieu Plate --------------------------------------------------

@testcase(1, "TC-001", "Nhieu xe khac lane cung thoi diem khong de du lieu nhau")
def tc_001(client, args):
    plate_in = random_plate("51A")
    plate_out = random_plate("51B")
    precond("Can it nhat 1 barrier ENTRY va 1 barrier EXIT thuoc cung 1 Pi, moi barrier co camera role=plate rieng.")
    print(f"  Lane vao: plate={plate_in!r} qua entry-plate-camera")
    print(f"  Lane ra : plate={plate_out!r} qua exit-plate-camera")

    def fire_entry():
        p = build_plate_event(args.entry_plate_camera_uuid, plate_in, 0.9, random.randint(1, 999999))
        publish_json(client, args.plate_topic, p)

    def fire_exit():
        p = build_plate_event(args.exit_plate_camera_uuid, plate_out, 0.9, random.randint(1, 999999))
        publish_json(client, args.plate_topic, p)

    t1 = threading.Thread(target=fire_entry)
    t2 = threading.Thread(target=fire_exit)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    expect("2 Redis session key khac nhau (detection:pending-platENTRY:... va ...EXIT:...) -> khong de/lan nhau.")
    note("Kiem tra Redis: KEYS 'detection:pending-plat*' phai thay 2 key rieng biet.")


@testcase(2, "TC-002", "Chon confidence cao nhat trong 1 chuoi burst (<8s)")
def tc_002(client, args):
    plate = args.plate if args.plate else random_plate("51A")
    seq = make_seq()
    confidences = [0.6, 0.75, 0.9]
    for i, conf in enumerate(confidences, start=1):
        seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, conf, seq, note=f"(burst {i}/3)")
        if i < len(confidences):
            wait(round(random.uniform(2, 3), 1), "cho giua cac message trong burst")
    expect(f"Redis session giu ban ghi confidence=0.9 (cao nhat), plateNumber={plate!r}, "
           f"TTL 150s duoc refresh sau moi message.")
    note("Kiem tra: HGETALL key detection:pending-platENTRY:...  -> field confidence phai = 0.9")


@testcase(3, "TC-003", "[RUI RO] Gap plate > 8s lam reset baseline confidence")
def tc_003(client, args):
    plate = args.plate if args.plate else random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.95, seq, note="(t=0s, confidence cao)")
    wait(9, "gia lap gap 9s > burst-gap 8s")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.55, seq, note="(t=9s, confidence thap hon)")
    expect("Theo code hien tai: session bi coi la 'xe moi' (gap>8s) -> RESET va GHI DE bang confidence=0.55, "
           "mat du lieu confidence=0.95 du van con trong TTL 150s.")
    note("Kiem tra: field confidence trong Redis phai = 0.55 (KHONG con la 0.95) neu dung nhu code hien tai.")


@testcase(4, "TC-004", "Plate het han TTL vi khong co face khop trong 150s")
def tc_004(client, args):
    plate = args.plate if args.plate else random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq, note="(chi 1 plate event duy nhat)")
    wait(args.session_ttl_seconds + 5, f"cho vuot TTL {args.session_ttl_seconds}s de session tu xoa")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     note="(face hop le nhung den qua tre)")
    expect("ingestFace khong tim thay session (da bi Redis tu xoa theo TTL) -> bo qua, KHONG tao "
           "parking_session, KHONG mo barrier.")


@testcase(5, "TC-005", "Plate confidence duoi nguong bi loai am tham")
def tc_005(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.3, seq, note="(confidence=0.3 < nguong 0.5)")
    expect("Bi AiEventQualityFilter.evaluatePlate loai ngay, KHONG tao session Redis, chi log debug noi bo "
           "-> khong co phan hoi nao cho operator/man hinh.")
    note("Kiem tra Redis: khong co key detection:pending-plat... nao duoc tao cho bien so nay.")


@testcase(6, "TC-006", 'Bien so rong (OCR fail nhung van ban event)')
def tc_006(client, args):
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, "", 0.8, seq,
                      note='(plateNumber="" — rong, khong phai null)')
    expect("Qua duoc check plateNumber==null (vi rong khac null) -> xu ly nhu 1 bien so hop le rong -> "
           "co the tao VehicleEntity voi plateNumber rong neu co face khop sau do.")


@testcase(7, "TC-007", "Camera plate chua duoc gan role trong barrier_camera")
def tc_007(client, args):
    precond(f"Can 1 camera co UUID hop le trong bang 'camera' NHUNG CHUA co dong barrier_camera role=plate "
            f"tro toi. Dang dung UUID placeholder: {args.unassigned_plate_camera_uuid} — hay truyen "
            f"--unassigned-plate-camera-uuid tro dung camera thuc te trong DB test, neu khong ket qua se "
            f"giong TC-008 (camera la hoan toan).")
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.unassigned_plate_camera_uuid, plate, 0.9, seq)
    expect("findBarrierByCameraIdAndRole rong -> publishErrorWhenHandlingEvent goi ban KHONG kem barrierId -> "
           "bi chan boi guard trong ParkingMessageService -> KHONG co canh bao nao toi thiet bi/man hinh, "
           "chi co log backend.")


@testcase(8, "TC-008", "Camera UUID la (chua sync/doi camera)")
def tc_008(client, args):
    fake_uuid = str(uuid.uuid4())
    seq = make_seq()
    plate = random_plate("51A")
    seq = send_plate(client, args, fake_uuid, plate, 0.9, seq,
                      note=f"(camera uuid ngau nhien, chac chan khong ton tai trong DB: {fake_uuid})")
    expect("cameraRepository.findCameraByUUID rong -> loi tuong tu TC-007, bi nuot do thieu barrierId.")


# --- B. Tin hieu Face ----------------------------------------------------

@testcase(9, "TC-009", "Face den truoc khi co plate ('plate truoc, face sau')")
def tc_009(client, args):
    seq = make_seq()
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     note="(gui thang face, KHONG gui plate truoc)")
    expect("ingestFace tim session rong -> bo qua hoan toan (log debug 'face_without_plate_pending'), "
           "khong luu, khong match.")


@testcase(10, "TC-010", "Face quality bien duoi tung nguong rieng le")
def tc_010(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq,
                      note="(plate mo duong cho cac face test bien duoi)")
    cases = [
        ("quality_score=0.49 (< 0.5)", {"score": 0.49}),
        ("det_score=0.59 (< 0.6)", {"detScore": 0.59}),
        ("blur_score=29 (< 30)", {"blurScore": 29.0}),
        ("yaw_deg=46 (> 45)", {"yawDeg": 46.0}),
        ("pitch_deg=46 (> 45)", {"pitchDeg": 46.0}),
    ]
    for desc, override in cases:
        seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good",
                         seq, quality_overrides=override, note=f"({desc})")
        wait(1, "giua cac truong hop bien")
    expect("Ca 5 truong hop deu bi AiEventQualityFilter.evaluateFace reject voi reason tuong ung "
           "(low_quality_score/low_det_score/blurry/yaw_out_of_range/pitch_out_of_range).")


@testcase(11, "TC-011", "Face thieu field 'usable' trong payload")
def tc_011(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     drop_paths=["face.quality.usable"], note="(khong co field usable trong payload)")
    expect("Code mac dinh usable=true khi thieu field -> event van duoc coi hop le du AI khong dam bao "
           "chat luong detect.")


@testcase(12, "TC-012", "[RUI RO NGHIEM TRONG] Face 'tre' tieu thu mat session cua face den dung gio ngay sau")
def tc_012(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq, note="(t=0s)")
    wait(125, "gia lap AI xu ly tre, face 'tre' den sau 125s (>120s business window nhung van <150s TTL)")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     note="(face #1 - 'tre', delta=125s > 120s)")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     note="(face #2 - gui ngay sau, gia lap 'dung gio' neu session con ton tai)")
    expect("ingestFace goi session.delete() TRUOC khi kiem tra delta -> session bi xoa ngay tu face #1 du "
           "khong match. Face #2 tim session rong -> KHONG match duoc, KHONG mo barrier, du gui gan nhu "
           "ngay lap tuc sau face #1.")


@testcase(13, "TC-013", "Tailgating do khong co loop sensor xac nhan xe")
def tc_013(client, args):
    plate_a = random_plate("51A")
    seq = make_seq()
    print("  Xe A: quet plate+face hop le -> barrier se mo binh thuong.")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate_a, 0.9, seq)
    wait(2, "gia lap xe A di toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    warn_manual("Xe B bam duoi xe A KHONG the mo phong bang MQTT message — ban chat rui ro chinh la KHONG "
                "co tin hieu AI nao duoc sinh ra cho xe B (camera khong kip bat rieng). Day la gioi han vat "
                "ly, khong phai gioi han cua script. De kiem chung thuc te: cho 2 xe di lien tiep qua barrier "
                "that va doi chieu so luong parking_session duoc tao voi so luong xe thuc te di qua.")


@testcase(14, "TC-014", "profileId thieu trong payload mac dinh ve 0")
def tc_014(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     drop_paths=["face.profileId"], note="(khong co field profileId trong payload)")
    expect("event.path(...).asInt() mac dinh tra 0 -> he thong coi profileId=0 la hop le, co the tao/khop "
           "nham ParkingCustomerEntity co aiProfileId=0.")


@testcase(15, "TC-015", "Nhieu khuon mat trong 1 khung hinh (xe cho 2 nguoi)")
def tc_015(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    profile_a = pick_profile_id(args)
    profile_b = profile_a + 1
    warn_manual("Script chi co the gui 2 event face KHAC NHAU gan nhau de minh hoa tinh huong mo ho — day "
                "KHONG phai mo phong dung viec AI chon nham nguoi trong 1 khung hinh (do la quyet dinh cua "
                "model AI, khong the ep buoc tu message).")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_a, args.face_confidence, "good", seq,
                     note="(gia lap AI chon 'nguoi ngoi sau')")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_b, args.face_confidence, "good", seq,
                     note="(gia lap neu AI gui tiep 'tai xe that')")
    expect("Backend chi nhan 1 profileId/event nen khong tu phat hien duoc neu AI chon nham nguoi. Can AI "
           "team xac nhan logic chon mat khi co nhieu mat trong khung hinh.")


@testcase(16, "TC-016", "Khuon mat bi che / dieu kien anh sang bat loi")
def tc_016(client, args):
    warn_manual("Day la field test thuc te (khau trang, non bao hiem, kinh ram, nguoc sang, thieu sang) — "
                "KHONG the mo phong dung qua MQTT message vi phu thuoc anh thuc te AI xu ly. Script chi gui "
                "duoc 1 face voi quality preset 'bad' de minh hoa nguong reject, khong thay the duoc test "
                "hien truong.")
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, 0.4, "bad", seq,
                     note="(quality preset 'bad' - mo phong gan dung che/toi)")
    expect("Du kien bi reject do quality_score/det_score/blur_score thap. Can do ti le that te theo tung "
           "khung gio/thoi tiet (sang som, trua nang gat, toi, mua).")


# --- C. Luong vao (ENTRY) -------------------------------------------------

@testcase(17, "TC-017", "[RUI RO BAO MAT] Xe ve thang vao KHONG can xac thuc khuon mat")
def tc_017(client, args):
    plate = args.plate if args.plate else "51A-111.11"
    precond(f"Can seed truoc trong DB: VehicleEntity plate={plate!r} co TicketEntity status=ACTIVE, "
            f"expireAt trong tuong lai.")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq, note="(KHONG gui face nao)")
    expect("handleRegisteredVehicle xu ly NGAY tai ENTRY (khong cho face) -> tao session, mo barrier chi "
           "dua vao bien so. Neu bien so nay bi gia mao/dan de, van vao duoc ma khong xac thuc chu xe.")


@testcase(18, "TC-018", "[RUI RO UX] Entry lap khi xe da co phien mo, khong co phan hoi")
def tc_018(client, args):
    plate = random_plate("51A")
    profile_id = pick_profile_id(args)
    seq = make_seq()
    print("  Lan 1 (entry hop le):")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho xe toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    wait(3, "gia lap bi quet ENTRY lan 2 cho cung bien so trong khi session van dang mo")
    print("  Lan 2 (entry lap lai, session van dang mo):")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    expect("alreadyOpenSession.isPresent() o lan 2 -> chi log info, return ngay — KHONG publish loi, KHONG "
           "mo barrier, KHONG co phan hoi nao cho tai xe/man hinh.")


@testcase(19, "TC-019", "Vehicle/Customer khong tim thay du plate/face match nhau")
def tc_019(client, args):
    plate = random_plate("99Z")
    profile_id = random.randint(900000, 999999)
    precond("Kich ban nay kho tao 'sach' vi PlateMessageService tu dong createNewVehicle khi plate chua ton "
            "tai. De dung mo phong 'vehicle/customer bi xoa sau khi da match', can xoa thu cong "
            "VehicleEntity/ParkingCustomerEntity tuong ung NGAY TRONG luc script dang cho o buoc duoi day.")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(5, "XOA THU CONG VehicleEntity/ParkingCustomerEntity lien quan trong DB ngay bay gio, truoc khi face toi")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    expect("vehicleRepository/customerRepository tra ve null -> publishErrorWhenHandlingEvent goi ban KHONG "
           "kem barrierId -> loi bi nuot (khong toi thiet bi), chi co trong log backend.")


@testcase(20, "TC-020", "Barrier/Lane/ParkingLot cau hinh thieu lien ket")
def tc_020(client, args):
    precond(f"Can 1 cap camera (face+plate) gan vao 1 barrier CHUA du lien ket parkingDevice->lane->parkingLot. "
            f"Dang dung UUID placeholder: plate={args.misconfigured_plate_camera_uuid} "
            f"face={args.misconfigured_face_camera_uuid} — hay truyen --misconfigured-plate-camera-uuid / "
            f"--misconfigured-face-camera-uuid tro dung camera thuc te trong DB test cua ban.")
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.misconfigured_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.misconfigured_face_camera_uuid, args.profile_id, args.face_confidence,
                     "good", seq)
    expect("Match thanh cong nhung barrier.getParkingDevice() hoac lane.getParkingLot() null -> bi tu choi, "
           "publishErrorWhenHandlingEvent LAN NAY CO kem barrierId -> loi toi duoc thiet bi (khac TC-007/008/019).")


@testcase(21, "TC-021", "Thieu anh bang chung nhung phien van duoc tao")
def tc_021(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq, drop_paths=["images"],
                      note="(khong co field images)")
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq,
                     drop_paths=["images"], note="(khong co field images)")
    expect("ParkingSessionEntity van duoc tao binh thuong, chi log loi noi bo 'Khong co anh luu db' — khong "
           "chan luong.")


@testcase(22, "TC-022", "Ve thang het han dung thoi diem quet (bien thoi gian)")
def tc_022(client, args):
    plate = args.plate if args.plate else "51A-111.11"
    precond(f"Chay lai testcase nay 3 lan rieng biet, moi lan seed truoc TicketEntity.expireAt cua "
            f"plate={plate!r} lan luot: (1) truoc thoi diem hien tai 1s, (2) dung bang thoi diem gui event, "
            f"(3) sau thoi diem hien tai 1s. Script chi gui event, KHONG tu set duoc expireAt trong DB.")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    expect("Dieu kien loc dung expireAt.isAfter(now) — xac nhan ve het han dung tai thoi diem quet co duoc "
           "coi ACTIVE hay khong theo tung moc da seed.")


@testcase(23, "TC-023", "AI tra trung profileId cho 2 nguoi khac nhau")
def tc_023(client, args):
    shared_profile_id = pick_profile_id(args)
    plate_1 = random_plate("51A")
    plate_2 = random_plate("51B")
    precond("Mo phong 2 'nguoi that khac nhau' nhung AI tra ve CUNG 1 profileId (loi AI hiem gap).")
    seq = make_seq()
    print("  Luot 1 (nguoi that A, xe A):")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate_1, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, shared_profile_id, args.face_confidence, "good", seq)
    wait(5, "cho xong luot 1, chuyen sang luot 2")
    print("  Luot 2 (nguoi that B, xe khac, NHUNG cung profileId do loi AI):")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate_2, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, shared_profile_id, args.face_confidence, "good", seq)
    expect("existsByAiProfileId=true o luot 2 -> he thong dung lai ParkingCustomerEntity cua nguoi thu nhat "
           "-> du lieu khach hang bi gan sai cho xe/nguoi thu 2.")


@testcase(24, "TC-024", "[RELEASE BLOCKER] Lenh mo barrier (MQTT) that bai sau khi da tao session trong DB")
def tc_024(client, args):
    warn_manual("Khong the mo phong broker/backend MQTT publish that bai tu chinh script nay (script va "
                "backend dung chung 1 broker — neu broker down thi script cung khong gui duoc gi). De tao "
                "dung loi nay can can thiep ha tang: vd tam thoi chan ket noi TU BACKEND toi broker "
                "(firewall/ACL) trong luc entry dang xu ly, roi mo lai de kiem tra trang thai DB vs trang "
                "thai barrier thuc te.")
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    expect("Neu publishOpenCommand that bai ngay sau khi session da save: ParkingSessionEntity ton tai trong "
           "DB (coi nhu 'da vao') nhung barrier vat ly KHONG nhan lenh mo -> xe dung ket truoc barrier.")


# --- D. Luong ra (EXIT) ----------------------------------------------------

@testcase(25, "TC-025", "[RELEASE BLOCKER] Exit khong tim thay phien ENTRY mo")
def tc_025(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    print("  Gui thang EXIT cho 1 bien so CHUA TUNG entry (khong co open session):")
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    expect("openSession=null -> publishErrorWhenHandlingEvent (CO barrierId nen toi duoc thiet bi) nhung "
           "KHONG co huong dan xu ly tiep theo, va KHONG co co che mo tay. Xe ket cung tai lan ra.")


@testcase(26, "TC-026", "[CRITICAL] Session dong trong DB truoc khi tao QR - payment fail lam xe ket vinh vien")
def tc_026(client, args):
    plate = random_plate("51A")
    profile_id = pick_profile_id(args)
    warn_manual("De trigger DUNG loi thanh toan, can tam thoi lam paymentGateway loi (vd sai config "
                "MegaPayConfig, chan network toi gateway) TRUOC khi buoc exit ben duoi chay.")
    seq = make_seq()
    print("  Buoc 1/2: entry hop le de tao open session (guest, khong ve thang):")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    wait(5, "BAT payment gateway loi NGAY BAY GIO truoc khi chay buoc exit")
    print("  Buoc 2/2: exit (ky vong payment gateway loi):")
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    expect("exitTime da duoc save truoc buoc tao QR. Neu tao QR loi: khong co QR, khong publishVehicleInfo, "
           "barrier khong mo. Quet lai exit lan nua se bao lai 'No open parking session found' -> ket vinh vien.")
    note("Thu chay lai TC-025 voi cung plate nay de xac nhan trang thai 'ket'.")


@testcase(27, "TC-027", "Ve thang con han tai thoi diem exit - mo thang khong thu phi")
def tc_027(client, args):
    plate = args.plate if args.plate else "51A-111.11"
    profile_id = pick_profile_id(args)
    precond(f"Can seed truoc: plate={plate!r} da co open ParkingSessionEntity (chay TC-017 truoc, hoac tu "
            f"san co) VA co TicketEntity ACTIVE, expireAt sau thoi diem exit ben duoi.")
    seq = make_seq()
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    expect("publishOpenForMonthlyTicket duoc goi, mo thang, khong tao payment. Neu lenh MQTT nay that bai, "
           "session cung da dong truoc do -> cung rui ro voi TC-026.")


@testcase(28, "TC-028", "Ve thang het han dung luc exit (bien isAfter/isEqual)")
def tc_028(client, args):
    plate = args.plate if args.plate else "51A-111.11"
    precond(f"Seed TicketEntity.expireAt cua plate={plate!r} = CHINH XAC thoi diem se gui event exit ben "
            f"duoi (can can dong ho DB va gio chay script).")
    seq = make_seq()
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    expect("Dieu kien dung isAfter(exitTime) || isEqual(exitTime) — xac nhan ve het han dung luc van duoc "
           "coi hop le (mo thang).")


@testcase(29, "TC-029", "Tinh phi khi ve thang het han giua phien gui xe")
def tc_029(client, args):
    plate = args.plate if args.plate else "51A-111.11"
    precond(f"Seed: plate={plate!r} entry luc ve con active, co it nhat 1 TicketEntity da EXPIRE (lich su) "
            f"voi expireAt nam GIUA entryTime va thoi diem exit se gui ben duoi.")
    seq = make_seq()
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    expect("calculatorService.calculate dung expireTicket.getExpireAt() lam moc bat dau tinh phi (khong phai "
           "entryTime) neu expireAt sau entryTime — can QA phoi hop CalculatorService de verify so tien.")


@testcase(30, "TC-030", "[RUI RO BAO MAT] Khong doi chieu khuon mat exit voi khuon mat entry")
def tc_030(client, args):
    plate = random_plate("51A")
    profile_a = pick_profile_id(args)
    profile_b = profile_a + 1
    seq = make_seq()
    print("  Entry bang khuon mat chu xe A:")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_a, args.face_confidence, "good", seq)
    wait(5, "gia lap thoi gian gui xe")
    print("  Exit cung bien so NHUNG khuon mat cua nguoi B (khac profileId):")
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, profile_b, args.face_confidence, "good", seq)
    expect("Matching tai EXIT chi dua vao barrier+lane session (plate + face bat ky khop thoi gian), KHONG "
           "so sanh profileId luc vao va luc ra -> nguoi B van lai xe ra duoc.")


@testcase(31, "TC-031", "Duplicate exit event cho cung 1 phien (double-submit)")
def tc_031(client, args):
    plate = random_plate("51A")
    profile_id = pick_profile_id(args)
    seq = make_seq()
    print("  Entry truoc de co open session:")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    wait(5, "gia lap thoi gian gui xe")
    print("  Exit lan 1:")
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    wait(1, "AI/MQTT resend ngay sau do (duplicate)")
    print("  Exit lan 2 (duplicate, gui lai gan nhu ngay lap tuc):")
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    expect("createSessionPayment goi failPendingPaymentsForSession truoc khi tao payment moi -> payment "
           "PENDING cu bi mark FAILED, chi con 1 QR active — xac nhan khong double-charge.")


# --- E. Loi thiet bi & ha tang ---------------------------------------------

@testcase(32, "TC-032", "Redis/Redisson khong kha dung khi ghi nhan plate")
def tc_032(client, args):
    warn_manual("Can DUNG THU CONG Redis truoc khi chay buoc nay: vd 'redis-cli SHUTDOWN NOSAVE' hoac dung "
                "container/service Redis o moi truong test. Script khong tu tat duoc Redis.")
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    expect("ingestPlate chi catch InterruptedException; loi ket noi Redis khac se nem ra ngoai — kiem tra "
           "log backend xem co bi catch o tang handle() cua PlateMessageService hay lam crash luong xu ly MQTT.")
    note("Nho khoi dong lai Redis sau khi test xong truoc khi chay cac testcase khac.")


@testcase(33, "TC-033", "Lock Redisson bi contention khi nhieu event don dap")
def tc_033(client, args):
    plate = random_plate("51K")
    n = args.tc033_concurrent_count
    print(f"--- Ban {n} plate event GAN NHU DONG THOI cung barrier+lane (mo phong gio cao diem) ---")

    def fire():
        payload = build_plate_event(args.entry_plate_camera_uuid, plate,
                                     round(random.uniform(0.6, 0.95), 3), random.randint(1, 999999))
        publish_json(client, args.plate_topic, payload)

    threads = [threading.Thread(target=fire) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    expect(f"tryLock cho toi da 2000ms; neu khong lay duoc lock -> log warn 'counld not acquire lock' va "
           f"RETURN, event bi DROP hoan toan, khong retry. Kiem tra log backend de dem so event thuc su "
           f"duoc xu ly (co the < {n} neu co contention).")


@testcase(34, "TC-034", "MQTT broker mat ket noi tam thoi giua Pi va backend")
def tc_034(client, args):
    warn_manual("KHONG the mo phong that qua script nay: day la hanh vi cua FIRMWARE tren Pi khi mat mang, "
                "khong phai hanh vi cua backend/AI event. Can test that tren thiet bi that: (1) ngat mang Pi, "
                "(2) cho 'xe' di qua (quet plate+face tai Pi that), (3) khoi phuc mang, (4) quan sat backend "
                "co nhan duoc event bi tre hay mat han khong.")
    note("Neu muon dung script nay de KIEM TRA TRANG THAI SAU KHI khoi phuc mang, co the gui 1 cap "
         "plate+face binh thuong (--tc1 hoac kich ban generic) roi doi chieu ket qua voi ky vong thuc te "
         "tren hien truong.")


@testcase(35, "TC-035", "Camera hong/offline hoan toan, khong ban duoc event nao")
def tc_035(client, args):
    warn_manual("Ban chat testcase nay la 'KHONG co gi de gui' — hay NGAT NGUON camera that (face hoac "
                "plate) va cho 1 xe dung truoc barrier, roi quan sat: khong co canh bao chu dong nao tu "
                "backend (khong heartbeat/health-check dinh ky theo code da doc), xe se cho vo thoi han, "
                "khong co manual override.")


@testcase(36, "TC-036", "Pi khoi dong lai giua luc co phien do dang")
def tc_036(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(args.tc036_restart_downtime, f"gia lap downtime Pi khoi dong lai ({args.tc036_restart_downtime}s)")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    expect(f"Redis KHONG mat session khi Pi restart (Redis nam o backend, khong phai tren Pi) — session van "
           f"con neu {args.tc036_restart_downtime}s < TTL 150s, face se match binh thuong. Neu downtime vuot "
           f"150s se roi vao truong hop giong TC-004.")


@testcase(37, "TC-037", "1 Pi quan ly ca 2 barrier (vao+ra) bi treo/loi dong thoi")
def tc_037(client, args):
    warn_manual("Day la kich ban ha tang (Pi treo/loi firmware anh huong dong thoi ca 4 camera cua 1 Pi), "
                "khong the mo phong bang MQTT message tu xa. Can test that: rut nguon/reset 1 Pi that dang "
                "quan ly ca 2 barrier, quan sat ca chieu vao LAN chieu ra cung ngung hoat dong dong thoi.")


# --- F. Van hanh thuc te ----------------------------------------------------

@testcase(38, "TC-038", "Dieu kien anh sang/thoi tiet bat loi lam tang ti le reject")
def tc_038(client, args):
    warn_manual("Field test thuc te theo khung gio/thoi tiet (sang som, trua nang gat, toi, mua) — khong "
                "the mo phong bang MQTT message gia lap. Can thu thap so lieu ti le reject that tu "
                "AiEventQualityFilter qua tung khung gio trong it nhat 1 tuan van hanh thu.")


@testcase(39, "TC-039", "Bien so ban/mo/dan decal - khong co phuong an nhap tay")
def tc_039(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    print("  Gia lap nhieu lan quet bien so kho doc (confidence thap lien tuc):")
    for i in range(3):
        conf = round(random.uniform(0.2, 0.45), 3)
        seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, conf, seq, note=f"(lan {i + 1}/3)")
        wait(2, "gia lap xe dung lai quet lai")
    expect("confidence lien tuc duoi nguong 0.5 -> xe khong vao duoc; can xac nhan co man hinh/app cho bao "
           "ve nhap tay bien so thay the hay khong.")


@testcase(40, "TC-040", "Khach vang lai lan dau vao bai (chua dang ky)")
def tc_040(client, args):
    plate = random_plate("29X")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq,
                      note="(bien so moi, chua tung vao bai)")
    wait(2, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, None, args.face_confidence, "good", seq,
                     note="(khach vang lai, khong co profileId dang ky)")
    expect("createNewVehicle tao VehicleEntity moi voi aiIdentityPlateId='UNVERIFIED_...'; vehicleType map "
           "dung theo object.label (CAR/MOTORBIKE).")


@testcase(41, "TC-041", "Tai xe dung lai qua lau giua luc quet plate va face (>120s)")
def tc_041(client, args):
    plate = random_plate("51A")
    seq = make_seq()
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(130, "tai xe xuong xe lam viec khac (130s, >120s business-window nhung <150s TTL)")
    seq = send_face(client, args, args.entry_face_camera_uuid, args.profile_id, args.face_confidence, "good", seq)
    expect("delta > businessWindowMs (120s) -> ingestFace tra stale, khong match — xe phai quet lai tu dau. "
           "Kiem tra UI co thong bao ro 'vui long quet lai' hay im lang (lien quan truc tiep TC-012).")


@testcase(42, "TC-042", "Gian lan rut ngan thoi gian gui xe (vao-ra rat nhanh)")
def tc_042(client, args):
    plate = random_plate("51A")
    profile_id = pick_profile_id(args)
    seq = make_seq()
    print("  Entry:")
    seq = send_plate(client, args, args.entry_plate_camera_uuid, plate, 0.9, seq)
    wait(1, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.entry_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    wait(3, "xe vao roi ra gan nhu ngay lap tuc (nghi van gian lan)")
    print("  Exit (chi vai giay sau entry):")
    seq = send_plate(client, args, args.exit_plate_camera_uuid, plate, 0.9, seq)
    wait(1, "cho toi vi tri quet mat")
    seq = send_face(client, args, args.exit_face_camera_uuid, profile_id, args.face_confidence, "good", seq)
    expect("Can verify calculatorService.calculate xu ly khoang thoi gian gan 0 phut co ap phi toi thieu "
           "hop ly hay tra ve 0d (ngoai pham vi code matching, can QA phoi hop module tinh phi).")


@testcase(43, "TC-043", "[TONG HOP] Khong co loi thoat van hanh khi he thong loi")
def tc_043(client, args):
    warn_manual("Testcase tong hop, khong phai 1 kich ban message rieng. Ra soat lai cac testcase da danh "
                "dau Blocker o tren trong boi canh KHONG co co che mo thu cong:")
    for tc_num in (12, 24, 25, 26, 34, 35):
        code, title, _ = TESTCASES[tc_num]
        print(f"    - {code}: {title}")
    note("Kien nghi: bo sung toi thieu 1 co che mo khan cap (co ghi log, can duyet/xac nhan) truoc khi "
         "release chinh thuc.")


# =====================================================================
# CLI
# =====================================================================

def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=os.environ.get("MQTT_HOST", "a97e22de.ala.dedicated.aws.emqxcloud.com"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MQTT_PORT", "1883")))
    parser.add_argument("--username", default=os.environ.get("MQTT_USERNAME", "dev"))
    parser.add_argument("--password", default=os.environ.get("MQTT_PASSWORD", "dev01"))
    parser.add_argument("--client-id", default=f"vehicle-simulator-{random.randint(1000, 9999)}")

    parser.add_argument("--face-topic", default="aibox/facesearch")
    parser.add_argument("--plate-topic", default="aibox/licenseplate")

    parser.add_argument("--entry-face-camera-uuid", default="8f660a77-49e2-4f5d-b113-1de0043a2a5b",
                         help="Phai khop camera role=face cua barrier ENTRY trong barrier_camera")
    parser.add_argument("--entry-plate-camera-uuid", default="d6efe615-46df-4ce9-966f-32138550ab54",
                         help="Phai khop camera role=plate cua barrier ENTRY trong barrier_camera")
    parser.add_argument("--exit-face-camera-uuid", default="88fc72c5-2e76-412b-b303-e972bc43c2bb",
                         help="Phai khop camera role=face cua barrier EXIT trong barrier_camera")
    parser.add_argument("--exit-plate-camera-uuid", default="599747d5-20c7-4819-b210-e4e3857462b1",
                         help="Phai khop camera role=plate cua barrier EXIT trong barrier_camera")

    parser.add_argument("--unassigned-plate-camera-uuid", default="00000000-0000-0000-0000-000000000000",
                         help="[TC-007] UUID cua 1 camera CO TON TAI trong bang 'camera' nhung CHUA gan "
                              "barrier_camera role=plate. Placeholder mac dinh se cho ra ket qua giong TC-008.")
    parser.add_argument("--misconfigured-plate-camera-uuid", default="00000000-0000-0000-0000-000000000000",
                         help="[TC-020] UUID camera plate thuoc 1 barrier thieu lien ket device/lane/parkingLot.")
    parser.add_argument("--misconfigured-face-camera-uuid", default="00000000-0000-0000-0000-000000000000",
                         help="[TC-020] UUID camera face thuoc cung barrier loi cau hinh o tren.")

    parser.add_argument("--plate", default="51A-111.11", help="Bien so 'that' se mo phong (kich ban generic / "
                                                                "cac testcase can bien so co san trong DB)")
    parser.add_argument("--plate-burst", type=int, default=5,
                         help="[Kich ban generic] So message plate gui lien tiep mo phong burst OCR "
                              "(0 = khong gui plate nao)")
    parser.add_argument("--plate-interval", type=float, default=1.0,
                         help="[Kich ban generic] Giay giua cac message plate trong burst")

    parser.add_argument("--face-delay", type=float, default=3.0,
                         help="[Kich ban generic] Giay cho truoc khi gui message face sau message plate cuoi")
    parser.add_argument("--session-ttl-seconds", type=float, default=150.0,
                         help="TTL cua plate session tren Redis o backend "
                              "(barrier.detection.session-ttl-seconds, mac dinh 150s). "
                              "Chi dung de in ket qua mong doi, khong anh huong toi viec gui message.")
    parser.add_argument("--profile-id", type=int, default=None,
                         help="profileId (bo trong = khach vang lai/Unknown, giong xe chua dang ky)")
    parser.add_argument("--face-confidence", type=float, default=0.87)
    parser.add_argument("--face-quality", choices=list(QUALITY_PRESETS.keys()), default="good",
                         help="[Kich ban generic] good = anh ro net; bad = mo/quay di")

    parser.add_argument("--tc033-concurrent-count", type=int, default=10,
                         help="[TC-033] So plate event ban dong thoi de mo phong lock contention")
    parser.add_argument("--tc036-restart-downtime", type=float, default=60.0,
                         help="[TC-036] So giay gia lap downtime khi Pi khoi dong lai")

    parser.add_argument("--list", action="store_true", help="Liet ke toan bo testcase (khong chay gi ca)")
    parser.add_argument("--tc-all", action="store_true",
                         help="Chay tuan tu TAT CA 43 testcase (rat lau do nhieu buoc cho TTL/business-window, "
                              "vd TC-004/012/041 moi cai da >120s) — bat buoc di kem --yes")
    parser.add_argument("--yes", action="store_true", help="Xac nhan chay khi dung --tc-all")

    parser.add_argument("--tc1", action="store_true",
                         help="Chay 1 testcase cu the, vi du --tc4 = TC-004. Dung --list de xem toan bo danh sach.")
    for i in range(2, 44):
        parser.add_argument(f"--tc{i}", action="store_true", help=argparse.SUPPRESS)

    return parser.parse_args()


def main():
    args = parse_args()

    if args.list:
        list_testcases()
        return

    requested = [i for i in range(1, 44) if getattr(args, f"tc{i}", False)]

    if args.tc_all:
        requested = list(range(1, 44))
        if not args.yes:
            print("--tc-all se chay tuan tu ca 43 testcase, uoc tinh mat vai chuc phut do cac buoc cho "
                  "TTL/business-window (TC-004, TC-012, TC-019, TC-026, TC-041 moi cai da co buoc cho "
                  ">=100s). Them --yes de xac nhan chay.")
            return

    client = create_client(args)
    client.connect(args.host, args.port, keepalive=30)
    client.loop_start()
    time.sleep(1.0)  # doi ket noi xong truoc khi publish

    try:
        if requested:
            for tc_num in requested:
                code, title, fn = TESTCASES[tc_num]
                print(f"\n===== {code}: {title} =====")
                fn(client, args)
                time.sleep(1.0)
        else:
            simulate_vehicle_entry(client, args)
        time.sleep(1.0)  # doi publish flush xong truoc khi disconnect
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
