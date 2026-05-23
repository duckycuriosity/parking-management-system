# Flow Explanation - Vehicle Entry/Exit Processing

## Overview

Tài liệu này mô tả chi tiết luồng xử lý khi một người đi vào bãi đỗ xe, từ sự kiện MQTT được nhận từ AI system cho đến khi barrier được mở. Bao gồm các thay đổi code và hành vi hiện tại.

---

## Thay Đổi Code (Code-Level Changes)

### 1. MQTTConfig.java
**File:** `src/main/java/vio/barie/core/config/mqtt/MQTTConfig.java`

**Thay đổi:**
- `setConnectionTimeout(10)` → `setConnectionTimeout(30)` (từ 10 giây → 30 giây)
- `setKeepAliveInterval(20)` → `setKeepAliveInterval(60)` (từ 20 giây → 60 giây)
- Bỏ `setMaxInFlightMessages(20)` (Paho 1.2.5 không hỗ trợ)
- Thêm bean `mqttExecutor()` - ThreadPoolTaskExecutor với:
  - Core pool size: 5 threads
  - Max pool size: 10 threads
  - Queue capacity: 100 tasks
  - Thread name prefix: "mqtt-handler-"

**Mục đích:**
- Tránh PING timeout (error 32002) khi network chậm hoặc broker đang xử lý
- Tránh EOFException (error 32109) khi MQTT thread bị block lâu
- Cung cấp executor pool để xử lý message async, giải phóng MQTT thread

---

### 2. MQTTSubcriber.java
**File:** `src/main/java/vio/barie/core/service/mqtt/MQTTSubcriber.java`

**Thay đổi:**
- Thay đổi callback `messageArrived()`:
  - **Cũ:** `listen(topic, payload);` (xử lý đồng bộ, block thread)
  - **Mới:** `mqttExecutor.execute(() -> listen(topic, payload));` (xử lý async)
- Thêm field: `private final Executor mqttExecutor;`
- Thay đổi từ `@RequiredArgsConstructor` sang custom constructor để inject `mqttExecutor`

**Mục đích:**
- Callback trả về ngay (< 1ms) → MQTT thread luôn free
- Xử lý JSON parsing, DB query, service calls chạy trên background threads
- Giảm contention, tránh PING timeout

---

## Flow Chi Tiết - Khi Người Đi Vào

### Bước 1: AI publish sự kiện
```
AI/Edge Device
    ↓
Publish sự kiện (plate hoặc face) vào MQTT broker
    ↓ topic: "aibox/licenseplate" hoặc "aibox/face"
MQTT Broker
```

---

### Bước 2: Paho client nhận và callback

**File & Method:** `MQTTSubcriber.messageArrived(String topic, MqttMessage message)`

```java
// OLD: messageArrived chạy trực tiếp trên MQTT thread (BLOCKING)
public void messageArrived(String topic, MqttMessage message) {
    String payload = new String(message.getPayload());
    log.info("Received from {}: {}", topic, payload);
    listen(topic, payload);  // ❌ block MQTT thread 1-2 giây
}

// NEW: messageArrived submit task rồi return ngay
public void messageArrived(String topic, MqttMessage message) {
    String payload = new String(message.getPayload());
    log.info("Received from {}: {}", topic, payload);
    // ✅ Async execute, callback return immediately
    mqttExecutor.execute(() -> listen(topic, payload));
}
```

**Kết quả:**
- MQTT thread **không block** → có thể gửi PING đúng giờ
- Callback trả về trong < 1ms
- Xử lý message chạy trên thread pool (5-10 threads)

---

### Bước 3: Listen & Dispatch

**File & Method:** `MQTTSubcriber.listen(String topic, String payload)`

```
listen(topic, payload)
  ↓
Parse JSON: JsonNode json = objectMapper.readTree(payload)
  ↓
Extract type: String type = json.path("type").asText("")
  ↓
dispatch(topic, type, json)
```

**File & Method:** `MQTTSubcriber.dispatch(String topic, String type, JsonNode json)`

Dựa vào topic:
- **Topic = aibox/face** → `ProfileMessageService.handle(json)`
- **Topic = aibox/licenseplate** → `PlateMessageService.handle(json)`
- **Topic = parking/v1/client/...** → `ParkingMessageService.handleConnect/Disconnect/Ping/Payment(...)`

---

### Bước 4a: Face Event

**File & Method:** `ProfileMessageService.handle(JsonNode event)`

```java
Long cameraCode = event.path("cameraId").asLong();
Integer profileId = event.path("profileId").asInt();
String profileName = event.path("name").asText(null);

// Tìm camera trong hệ thống
CameraEntity aiCamera = cameraRepository.findCameraByAiCameraId(cameraCode).orElse(null);

// Tìm lane qua camera
CameraEntity cameraInLane = cameraRepository.findByCodeWithLane(aiCamera.getId().toString()).orElse(null);
long laneId = cameraInLane.getLane().getId();

// Tạo customer nếu chưa có
if(customerRepository.findByAiProfileId(profileId).isEmpty()) {
    ParkingCustomerEntity newCustomer = new ParkingCustomerEntity();
    newCustomer.setAiProfileId(profileId);
    newCustomer.setUsername(profileName);
    customerRepository.save(newCustomer);
}

// Đẩy event vào buffer
aiEventBuffer.addFaceEvent(laneId, profileId);
```

**Kết luận:** 
- Xác định `laneId` từ camera
- Tạo customer record (nếu cần)
- Đẩy sự kiện face vào buffer chờ plate

---

### Bước 4b: Plate Event

**File & Method:** `PlateMessageService.handle(JsonNode event)`

```java
Long cameraCode = event.path("cameraId").asLong();
String plateNumber = event.path("plate").path("plate").asText(null);
String plateId = event.path("plate").path("cropImage").asText(null);

// Tìm camera
CameraEntity aiCamera = cameraRepository.findCameraByAiCameraId(cameraCode).orElse(null);

// Tìm lane
CameraEntity cameraInLane = cameraRepository.findByCodeWithLane(aiCamera.getId().toString()).orElse(null);
long laneId = cameraInLane.getLane().getId();

// Tạo vehicle nếu chưa có
if(vehicleRepository.findByAiIdentityPlateId(plateId).isEmpty()) {
    VehicleEntity newVehicle = new VehicleEntity();
    newVehicle.setPlateNumber(plateNumber);
    newVehicle.setAiIdentityPlateId(plateId);
    vehicleRepository.save(newVehicle);
}

// ⭐ PUBLISH SỚM: báo hardware có xe tới (trước khi match)
parkingMessageService.publishEntryVehicleInfo(device, plateNumber, LocalDateTime.now());

// Đẩy event vào buffer chờ match với face
aiEventBuffer.addPlateEvent(laneId, plateNumber, plateId);
```

**Kết luận:**
- Xác định `laneId` từ camera
- Tạo vehicle record (nếu cần)
- **Publish sớm:** gửi "vehicle_info" cho hardware biết có xe tới (chuẩn bị camera, display)
- Đẩy sự kiện plate vào buffer

---

### Bước 5: Event Matching (Buffer)

**File & Method:** `AiMQTTEventBuffer.addPlateEvent() / addFaceEvent()`

**Cơ chế buffer:**
```
ConcurrentHashMap<laneId, PendingLaneEvent> buffer

PendingLaneEvent {
  volatile PendingFaceEvent face;
  volatile PendingPlateEvent plate;
}
```

**Khi plate event tới (ví dụ):**
```java
public void addPlateEvent(long laneId, String plateNumber, String aiPlateId) {
    AtomicReference<PendingFaceEvent> matched = new AtomicReference<>();
    
    buffer.compute(laneId, (id, existing) -> {
        if (existing != null && existing.face != null && !existing.face.isExpired(TIMEOUT_MS)) {
            // ✅ ĐÃ CÓ FACE CHƯA HẾT HẠN → MATCH!
            matched.set(existing.face);
            return null;  // xóa state khỏi buffer
        }
        // Tạo / cập nhật lane state
        PendingLaneEvent next = existing != null ? existing : new PendingLaneEvent();
        next.plate = new PendingPlateEvent(plateNumber, aiPlateId, System.currentTimeMillis());
        return next;
    });
    
    PendingFaceEvent face = matched.get();
    if (face != null) {
        // ✅ CHO PHÉP MATCH → TẠO PARKING EVENT
        trigger(plateNumber, aiPlateId, face.profileId(), laneId);
    }
}
```

**Lưu ý `AtomicReference`:**
- Lambda `compute()` không cho phép gán out-of-scope variable
- `matched` là cách để "đưa" matched face result ra khỏi lambda
- `matched.get()` sau lambda để lấy kết quả

**TIMEOUT_MS = 60_000:** nếu face/plate quá cách nhau > 60 giây thì hết hạn (xóa)

---

### Bước 6: Trigger & Create Parking Event

**File & Method:** `AiMQTTEventBuffer.trigger(String plateNumber, String aiPlateId, Integer profileId, long laneId)`

```java
private void trigger(String plateNumber, String aiPlateId, Integer profileId, long laneId) {
    try {
        ParkingEvent event = new ParkingEvent();
        event.setPlateNumber(plateNumber);
        event.setAiIdentityPlateId(aiPlateId);
        event.setAiIdentityCustomerId(profileId);
        event.setLaneId(laneId);
        parkingService.handle(event);
        log.info("AiEventBuffer: matched — laneId={}, plate={}, profileId={}", 
                 laneId, plateNumber, profileId);
    } catch (Exception ex) {
        log.error("AiEventBuffer: handle event failed for laneId={}: {}", 
                 laneId, ex.getMessage(), ex);
    }
}
```

**Kết quả:**
- Tạo `ParkingEvent` với lane + plate + profile
- Gọi `ParkingService.handle(event)` để xử lý business logic (entry/exit)

---

### Bước 7: Business Logic - Entry vs Exit

**File & Method:** `ParkingService.handle(ParkingEvent req)`

```java
public void handle(ParkingEvent req) {
    VehicleEntity vehicle = findVehicle(req);  // lookup vehicle
    
    // ⭐ TÌM SESSION CÓ ĐANG MỞ KHÔNG?
    Optional<ParkingSessionEntity> session = parkingSessionRepository
            .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId());
    
    if (session.isPresent()) {
        handleExitEvent(req);  // Xe đang trong bãi → tiến hành EXIT
    } else {
        handleEntryEvent(req); // Xe chưa vào → tiến hành ENTRY
    }
}
```

**Trường hợp 1: ENTRY (chưa có session)**

**File & Method:** `ParkingService.handleEntryEvent(ParkingEvent req)`

```java
@Transactional
public void handleEntryEvent(ParkingEvent req) {
    VehicleEntity vehicle = findVehicle(req);
    ParkingCustomerEntity customer = findCustomer(req);
    LaneEntity lane = findLane(req);
    
    // 1️⃣ TẠO SESSION
    ParkingSessionEntity session = new ParkingSessionEntity();
    session.setVehicle(vehicle);
    session.setEntryCustomer(customer);
    session.setEntryTime(LocalDateTime.now());
    session.setEntryLane(lane);
    session.setParkingLot(lane.getParkingLot());
    parkingSessionRepository.save(session);
    
    // 2️⃣ RESOLVE BARRIER
    String barrierId = resolveBarrierId(req, lane);
    
    log.info("handleEntryEvent: entry session created — vehicleId={}, plate={}, laneId={}",
             vehicle.getId(), vehicle.getPlateNumber(), req.getLaneId());
    
    // 3️⃣ PUBLISH OPEN COMMAND
    ioTService.publishOpenCommand(barrierId, session.getId(), "vehicle_entry");
}
```

---

### Bước 8: Resolve Barrier ID

**File & Method:** `ParkingService.resolveBarrierId(ParkingEvent req, LaneEntity lane)`

```java
private String resolveBarrierId(ParkingEvent req, LaneEntity lane) {
    // 1️⃣ Nếu event đã có barrierId → dùng luôn
    if (req.getBarrierId() != null && !req.getBarrierId().isBlank()) {
        return req.getBarrierId();
    }
    
    // 2️⃣ Tìm ParkingDevice của lane
    Optional<ParkingDeviceEntity> device = parkingDeviceRepository
            .findByLane_Id(lane.getId()).stream().findFirst();
    if (device.isEmpty()) {
        throw new ServiceException("Không tìm thấy parking device cho lane", 404);
    }
    
    // 3️⃣ Lấy barrier đầu tiên của device
    return barrierRepository.findAllByParkingDevice_Id(device.get().getId()).stream()
            .map(BarrierEntity::getBarrierId)
            .findFirst()
            .orElseThrow(() -> new ServiceException("Không tìm thấy barrier cho lane", 404));
}
```

**Quy tắc ưu tiên:**
1. Nếu `ParkingEvent.barrierId` đã có → dùng luôn (trang đó sẽ được set nếu có matching service cụ thể barrier)
2. Else: tìm device của lane → lấy barrier đầu tiên

**⚠️ Ghi chú:** Hiện logic chỉ lấy barrier đầu tiên; nếu muốn chọn barrier thực sự được suy đoán từ camera, cần mapping camera→barrier đầy đủ và set `req.setBarrierId(...)` trong trigger.

---

### Bước 9: Publish Open Command

**File & Method:** `ParkingMessageService.publishOpenCommand(String barrierId, Long sessionId, String reason)`

```java
public void publishOpenCommand(String barrierId, Long sessionId, String reason) {
    if (barrierId == null) {
        log.warn("publishOpenCommand: barrierId is null, skipping — sessionId={}", sessionId);
        return;
    }
    
    // Tạo object command
    OpenCmdCommand cmd = OpenCmdCommand.builder()
            .barrierId(barrierId)      // ⭐ Dùng barrierId trực tiếp (không nối "_01")
            .sessionId(String.valueOf(sessionId))
            .reason(reason)            // "vehicle_entry"
            .playSound(true)
            .soundFile("welcome.mp3")
            .autoCloseSeconds(5)
            .build();
    
    publish(barrierId, cmd);
}

private void publish(String barrierId, Object payload) {
    try {
        // Ghép topic: prefix + "/" + deviceId + suffix
        String topic = prefix + "/" + barrierId + suffix;
        String json = JSONUtil.toJsonStr(payload);
        mqttPublisher.publish(topic, json);
        log.info("Published cmd to topic={}", topic);
    } catch (Exception e) {
        log.error("Failed to publish to barrierId={}: {}", barrierId, e.getMessage(), e);
    }
}
```

**Topic Output:**
- `publish-cmd-topic-prefix` = `"parking/v1/server"` (config)
- Topic final = `"parking/v1/server/" + barrierId + "/cmd"`
- Ví dụ: `"parking/v1/server/pk_df102790fdca47ad_01/cmd"`

---

### Bước 10: Actual MQTT Publish

**File & Method:** `MQTTPublisher.publish(String topic, String payload)`

```java
public void publish(String topic, String payload) {
    try {
        MqttMessage message = new MqttMessage(payload.getBytes());
        client.publish(topic, message);
        log.debug("Message published to topic: {}", topic);
    } catch (MqttException e) {
        log.error("Publish error", e);
    }
}
```

**Kết quả:**
- Payload JSON được gửi tới Paho MQTT client
- Client publish tới broker
- Barrier device nhận message từ broker
- Device mở barrier

---

## Trường Hợp 2: EXIT (có session đang mở)

**File & Method:** `ParkingService.handleExitEvent(ParkingEvent req)`

```java
public void handleExitEvent(ParkingEvent req) {
    VehicleEntity vehicle = findVehicle(req);
    ParkingCustomerEntity customer = findCustomer(req);
    LaneEntity lane = findLane(req);
    
    // 1️⃣ TÌM SESSION ĐANG MỞ
    ParkingSessionEntity session = parkingSessionRepository
            .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId())
            .orElseThrow(() -> new ServiceException("Không tìm thấy hoạt động đi vào của xe", 404));
    
    session.setExitCustomer(customer);
    session.setExitTime(LocalDateTime.now());
    session.setExitLane(lane);
    
    String barrierId = resolveBarrierId(req, lane);
    
    // 2️⃣ KIỂM VÒNG CẤP THÁNG
    TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
    if (ticket != null) {
        parkingSessionRepository.save(session);
        ioTService.publishOpenForMonthlyTicket(barrierId, session.getId(), 
                                              vehicle.getPlateNumber(), 
                                              "parking_customer_monthly", 
                                              ticket.getExpireAt());
        return;
    }
    
    // 3️⃣ LUỒNG VÉ LƯỢT (tính tiền + QR + publish info)
    int amount = calculatorService.calculate(session.getEntryTime(), LocalDateTime.now());
    
    CreatePaymentRequest payReq = new CreatePaymentRequest();
    payReq.setSession(session);
    payReq.setAmount(amount);
    payReq.setGoodsName("Phi gui xe");
    payReq.setDescription("Phi gui xe bien so " + vehicle.getPlateNumber());
    CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);
    
    parkingSessionRepository.save(session);
    
    // 3a️⃣ PUBLISH THÔNG TIN XE CHO THANH TOÁN
    ioTService.publishVehicleInfo(barrierId, session.getId(), 
                                  vehicle.getPlateNumber(),
                                  session.getEntryTime(), amount, 
                                  paymentService.decodePaymentLink(qr.getPaymentLink()));
    
    // 3b️⃣ PUBLISH XÁC NHẬN THANH TOÁN (sau 3s)
    try {
        Thread.sleep(3_000L);
        ioTService.publishPaymentConfirmed(barrierId, session.getId(), amount, qr.getInvoiceNo());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    
    // 3c️⃣ PUBLISH THÀNH CÔNG & MỞ CỔNG (sau thêm 3s)
    try {
        Thread.sleep(3_000L);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    ioTService.publishPaymentSuccess(barrierId, session.getId(), vehicle.getPlateNumber());
}
```

**Flow EXIT:**
- Tìm session đang mở của vehicle
- Nếu có vé tháng hợp lệ → mở cổng ngay (no payment needed)
- Nếu vé lượt:
  - Tính tiền
  - Tạo QR payment link
  - Publish vehicle_info (thiết bị hiển thị info + QR)
  - Chờ 3s → publish payment_confirmed
  - Chờ thêm 3s → publish payment_success → mở cổng

---

## Cleanup & Expiration

**File & Method:** `AiMQTTEventBuffer.cleanupExpired()`

```java
@Scheduled(fixedDelay = 3_000)
void cleanupExpired() {
    buffer.forEach((laneId, pending) ->
        buffer.compute(laneId, (id, p) -> {
            if (p == null) return null;
            
            // Xóa face event nếu quá hạn
            if (p.face != null && p.face.isExpired(TIMEOUT_MS)) {
                log.warn("AiEventBuffer: face event timed out — laneId={}, profileId={}", 
                         id, p.face.profileId());
                p.face = null;
            }
            
            // Xóa plate event nếu quá hạn
            if (p.plate != null && p.plate.isExpired(TIMEOUT_MS)) {
                log.warn("AiEventBuffer: plate event timed out — laneId={}, plate={}", 
                         id, p.plate.plateNumber());
                p.plate = null;
            }
            
            // Nếu cả 2 đều xóa → xóa lane entry khỏi buffer
            return (p.face == null && p.plate == null) ? null : p;
        })
    );
}
```

**Chạy mỗi 3 giây:** xóa face/plate event cũ hơn 60 giây (TIMEOUT_MS)

---

## Concurrency & Thread Safety

### MQTT Thread vs Handler Thread

| Thành phần | Thread | Hành vi |
|----------|--------|--------|
| MQTT Paho client callback | MQTT receiver thread | Chỉ submit task → return ngay (< 1ms) |
| Message listen/dispatch | Executive pool (mqtt-handler-*) | Parse JSON, call service (1-2s) |
| Buffer insert/match | mqtt-handler-* | Use `ConcurrentHashMap.compute()` for atomic ops |
| ParkingService call | mqtt-handler-* | Create session, write DB (async) |
| Publish to device | mqtt-handler-* | Send MQTT message (async) |

**Lợi ích:**
- MQTT ping sender thread luôn free → PING sẽ được gửi đúng giờ
- Xử lý dài (DB query, JSON parse) không block MQTT thread
- Không timeout 32002 hay 32109

### Data Structure Thread-Safety

- `ConcurrentHashMap<laneId, PendingLaneEvent>`: multiple handler threads yên tâm insert/match
- `AtomicReference<>`: capture match result từ lambda compute()
- No explicit lock in current `AiMQTTEventBuffer` (relying on ConcurrentHashMap atomicity)

---

## Summary: Sequence Diagram

```
AI Device
    ↓ MQTT publish (plate/face)
Broker
    ↓ forward
MQTT Client (Paho)
    ↓ callback (this thread only submits → return fast)
MQTTSubcriber.messageArrived()  (MQTT thread)
    ↓ async
Executor pool (mqtt-handler-1, 2, 3...)
    ↓
MQTTSubcriber.listen()
    ↓
MQTTSubcriber.dispatch()
    ↓ (face path)           ↓ (plate path)
ProfileMessageService      PlateMessageService
    ↓                          ↓
findLane + addFaceEvent    publishEntryVehicleInfo + addPlateEvent
    ↓                          ↓
AiMQTTEventBuffer (compute λ)
    ↓ (if match)
trigger() → create ParkingEvent
    ↓
ParkingService.handle()
    ↓ (if entry)   ↓ (if exit)
handleEntry    handleExit
    ↓              ↓
Create session + publish open command
    ↓              ↓
ParkingMessageService.publishOpenCommand()
    ↓              ↓
MQTTPublisher.publish()
    ↓              ↓
Broker → device opens barrier
```

---

## Key Points

1. **Async MQTT Handling:** Callback không block → PING luôn gửi được
2. **Publish Early:** Plate event publish vehicle_info ngay (even before face match)
3. **Buffer Matching:** Face + Plate comparison trong 60 giây window
4. **Barrier Resolution:** Hiện dùng barrier đầu tiên; cần mapping camera→barrier để chọn đúng
5. **Session Tracking:** Check session tồn tại → Entry vs Exit logic
6. **Transactional:** DB changes wrapped in `@Transactional`
7. **Error Handling:** Try-catch ở các service layer, log error nếu không tìm thấy entity

---

## Future Improvements (For Barrier-Aware Matching)

Để tự động chọn barrier đúng khi có 2 barrier + 4 camera:

1. **Add barrier_camera table:** map camera → barrier
2. **Update PlateMessageService/ProfileMessageService:** lookup barrier from camera-mapping
3. **Extend ParkingEvent:** add `barrierId` field
4. **Update trigger():** set `event.setBarrierId(resolvedBarrierId)` before calling parkingService
5. **DetectionMatchingService (optional):** phức tạp hơn, match by barrier group vs lane

---

**Last Updated:** May 13, 2026
