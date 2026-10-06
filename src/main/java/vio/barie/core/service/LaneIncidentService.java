package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.LaneIncidentActionType;
import vio.barie.core.common.LaneIncidentStatus;
import vio.barie.core.common.LaneIncidentType;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.filter.UserContext;
import vio.barie.core.dto.LaneEventCommand;
import vio.barie.core.dto.LaneEventImage;
import vio.barie.core.dto.LaneIncidentResponse;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.dto.user.UserDTO;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.LaneIncidentActionEntity;
import vio.barie.core.entity.LaneIncidentEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.LaneIncidentActionRepository;
import vio.barie.core.repository.LaneIncidentRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.smoothing.SmoothingAIEvents;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.utils.PlateNumberUtils;
import vio.barie.core.verification.ParkingSessionLifecycleService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LaneIncidentService {

    public static final String OUTCOME_SESSION_CLOSED = "SESSION_CLOSED";
    public static final String OUTCOME_SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    public static final String OUTCOME_FACE_MISMATCH = "FACE_MISMATCH";
    public static final String OUTCOME_BARRIER_OPENED = "BARRIER_OPENED";

    private static final List<LaneIncidentStatus> ACTIVE_STATUSES =
            List.of(LaneIncidentStatus.PENDING, LaneIncidentStatus.AWAITING_DECISION);

    private final LaneIncidentRepository laneIncidentRepository;
    private final LaneIncidentActionRepository laneIncidentActionRepository;
    private final BarrierRepository barrierRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionLifecycleService sessionLifecycleService;
    private final ParkingMessageService ioTService;
    private final SmoothingAIEvents smoothingAIEvents;

    @Value("${barrier.incident.active-ttl-seconds:600}")
    private long incidentActiveTtlSeconds;

    /**
     * Ghi nhận sự cố khuôn mặt lúc ra không khớp lúc vào. Nếu phiên này đã có sự cố đang chờ
     * (xe vẫn đứng trong khung hình, AI bắn event liên tục) thì trả lại sự cố cũ, không tạo mới.
     */
    @Transactional
    public LaneIncidentEntity reportFaceMismatch(ParkingSessionEntity session, Integer entryProfileId,
                                                 PendingEventDetection detection) {
        return laneIncidentRepository
                .findFirstBySession_IdAndTypeAndStatus(session.getId(), LaneIncidentType.FACE_MISMATCH,
                        LaneIncidentStatus.PENDING)
                .orElseGet(() -> {
                    LaneIncidentEntity incident = new LaneIncidentEntity();
                    incident.setType(LaneIncidentType.FACE_MISMATCH);
                    incident.setStatus(LaneIncidentStatus.PENDING);
                    incident.setLaneId(detection.getExitLaneId());
                    incident.setBarrierId(detection.getExitBarrierId());
                    incident.setSession(session);
                    incident.setDetectedPlate(detection.getPlateNumber());
                    incident.setEntryProfileId(entryProfileId);
                    incident.setExitProfileId(detection.getProfileId());
                    incident.setExitImageInfo(detection.getListImageInfo());
                    incident.setDetectionPayload(detection);
                    laneIncidentRepository.save(incident);
                    log.info("reportFaceMismatch: incident created id={} sessionId={} plate={} entryProfile={} exitProfile={}",
                            incident.getId(), session.getId(), detection.getPlateNumber(), entryProfileId,
                            detection.getProfileId());
                    return incident;
                });
    }

    /**
     * Lượt quét tại làn ra không tìm được phiên gửi xe: lưu sự cố ngay để admin xác nhận lại biển số.
     * Khi làn đang có sự cố chờ xử lý (xe quét lại) thì trả lại sự cố đó, không tạo mới.
     */
    @Transactional
    public LaneIncidentEntity reportSessionNotFound(PendingEventDetection detection) {
        Optional<LaneIncidentEntity> active = laneIncidentRepository
                .findFirstByLaneIdAndTypeAndStatusInOrderByCreatedAtDesc(detection.getExitLaneId(),
                        LaneIncidentType.SESSION_NOT_FOUND, ACTIVE_STATUSES);
        if (active.isPresent()) {
            LaneIncidentEntity incident = active.get();
            LocalDateTime expireBefore = LocalDateTime.now().minusSeconds(incidentActiveTtlSeconds);
            if (incident.getCreatedAt() == null || incident.getCreatedAt().isAfter(expireBefore)) {
                return incident;
            }
            // Sự cố cũ không ai xử lý (xe đã rời làn): đóng lại để không chặn xe sau
            expire(incident);
        }

        LaneIncidentEntity incident = new LaneIncidentEntity();
        incident.setType(LaneIncidentType.SESSION_NOT_FOUND);
        incident.setStatus(LaneIncidentStatus.PENDING);
        incident.setLaneId(detection.getExitLaneId());
        incident.setBarrierId(detection.getExitBarrierId());
        incident.setDetectedPlate(detection.getPlateNumber());
        incident.setExitProfileId(detection.getProfileId());
        incident.setExitImageInfo(detection.getListImageInfo());
        incident.setDetectionPayload(detection);
        laneIncidentRepository.save(incident);
        log.info("reportSessionNotFound: incident created id={} lane={} plate={} profileId={}",
                incident.getId(), incident.getLaneId(), incident.getDetectedPlate(), incident.getExitProfileId());
        return incident;
    }

    /**
     * Xe quét lại và ra được bình thường: sự cố không tìm thấy phiên còn treo ở làn này không cần xử lý nữa.
     */
    @Transactional
    public void expireActiveSessionNotFound(Long laneId) {
        laneIncidentRepository
                .findFirstByLaneIdAndTypeAndStatusInOrderByCreatedAtDesc(laneId, LaneIncidentType.SESSION_NOT_FOUND,
                        ACTIVE_STATUSES)
                .ifPresent(this::expire);
    }

    private void expire(LaneIncidentEntity incident) {
        incident.setStatus(LaneIncidentStatus.EXPIRED);
        incident.setResolvedAt(LocalDateTime.now());
        laneIncidentRepository.saveAndFlush(incident);
        log.info("expire: incident id={} expired", incident.getId());
    }

    @Transactional(readOnly = true)
    public List<LaneIncidentResponse> getPendingIncidents(Long laneId) {
        return laneIncidentRepository.findActiveIncidents(laneId).stream()
                .map(incident -> toResponse(incident, null))
                .toList();
    }

    /**
     * Để admin tự xác nhận, nếu đúng thì tính payment như bình thường.
     */
    @Transactional
    public LaneIncidentResponse approve(Long incidentId, String note) {
        LaneIncidentEntity incident = lockActiveIncident(incidentId, LaneIncidentType.FACE_MISMATCH);

        ParkingSessionEntity session = incident.getSession();
        if (session == null || session.getExitTime() != null) {
            throw new BusinessException("Phiên gửi xe của sự cố này đã được đóng", "SESSION_ALREADY_CLOSED", 409);
        }

        PendingEventDetection detection = requireDetection(incident);
        BarrierEntity barrier = requireBarrier(incident.getBarrierId());
        ParkingCustomerEntity exitCustomer = findExitCustomer(incident);

        recordAction(incident, LaneIncidentActionType.APPROVE_FACE, note, null, null, OUTCOME_SESSION_CLOSED);
        closeIncident(incident, LaneIncidentStatus.RESOLVED);
        LaneIncidentResponse response = toResponse(incident, OUTCOME_SESSION_CLOSED);

        sessionLifecycleService.closeExit(session, exitCustomer, barrier, detection);
        log.info("approve: incident id={} approved, session id={} closed", incident.getId(), session.getId());
        return response;
    }

    /**
     * Admin xác nhận/nhập lại biển số cho sự cố không tìm thấy phiên, rồi tìm lại phiên theo biển số đó.
     * Tìm thấy và đúng khuôn mặt thì đóng phiên như lượt ra bình thường; sai khuôn mặt thì chuyển sang
     * sự cố FACE_MISMATCH; vẫn không thấy phiên thì chờ admin quyết định mở barrier hay không.
     */
    @Transactional
    public LaneIncidentResponse submitPlate(Long incidentId, String rawPlateNumber, String note) {
        LaneIncidentEntity incident = lockActiveIncident(incidentId, LaneIncidentType.SESSION_NOT_FOUND);

        String plateNumber = PlateNumberUtils.normalize(rawPlateNumber);
        if (!PlateNumberUtils.isValid(plateNumber)) {
            throw new BusinessException("Biển số không đúng quy ước biển số xe Việt Nam", "PLATE_INVALID", 400);
        }
        PendingEventDetection detection = requireDetection(incident);

        LaneIncidentActionType actionType = plateNumber.equalsIgnoreCase(incident.getDetectedPlate())
                ? LaneIncidentActionType.CONFIRM_PLATE : LaneIncidentActionType.CORRECT_PLATE;
        incident.setCorrectedPlate(plateNumber);

        VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plateNumber);
        ParkingSessionEntity session = vehicle == null ? null
                : sessionLifecycleService.findOpenSessionForVehicle(vehicle.getId()).orElse(null);

        if (session == null) {
            recordAction(incident, actionType, note, incident.getDetectedPlate(), plateNumber, OUTCOME_SESSION_NOT_FOUND);
            incident.setStatus(LaneIncidentStatus.AWAITING_DECISION);
            laneIncidentRepository.save(incident);
            log.info("submitPlate: incident id={} plate={} still has no open session", incident.getId(), plateNumber);
            return toResponse(incident, OUTCOME_SESSION_NOT_FOUND);
        }

        detection.setPlateNumber(plateNumber);
        ParkingCustomerEntity entryCustomer = session.getEntryCustomer();
        Integer entryProfileId = entryCustomer != null ? entryCustomer.getAiProfileId() : null;
        incident.setSession(session);
        incident.setEntryProfileId(entryProfileId);

        if (entryProfileId != null && !entryProfileId.equals(incident.getExitProfileId())) {
            recordAction(incident, actionType, note, incident.getDetectedPlate(), plateNumber, OUTCOME_FACE_MISMATCH);
            closeIncident(incident, LaneIncidentStatus.RESOLVED);
            LaneIncidentEntity faceIncident = reportFaceMismatch(session, entryProfileId, detection);
            publishFaceMismatchConfirm(faceIncident, session, vehicle);
            log.info("submitPlate: incident id={} plate={} found session id={} but face mismatched, face incident id={}",
                    incident.getId(), plateNumber, session.getId(), faceIncident.getId());
            return toResponse(faceIncident, OUTCOME_FACE_MISMATCH);
        }

        BarrierEntity barrier = requireBarrier(incident.getBarrierId());
        ParkingCustomerEntity exitCustomer = findExitCustomer(incident);

        recordAction(incident, actionType, note, incident.getDetectedPlate(), plateNumber, OUTCOME_SESSION_CLOSED);
        closeIncident(incident, LaneIncidentStatus.RESOLVED);
        LaneIncidentResponse response = toResponse(incident, OUTCOME_SESSION_CLOSED);

        sessionLifecycleService.closeExit(session, exitCustomer, barrier, detection);
        log.info("submitPlate: incident id={} plate={} resolved, session id={} closed", incident.getId(), plateNumber,
                session.getId());
        return response;
    }

    /**
     * Đã xác nhận biển số mà vẫn không có phiên hợp lệ: admin quyết định mở barrier thủ công.
     */
    @Transactional
    public LaneIncidentResponse manualOpen(Long incidentId, String note) {
        LaneIncidentEntity incident = lockActiveIncident(incidentId, LaneIncidentType.SESSION_NOT_FOUND);
        if (incident.getStatus() != LaneIncidentStatus.AWAITING_DECISION) {
            throw new BusinessException("Cần xác nhận biển số trước khi mở barrier thủ công", "PLATE_NOT_CONFIRMED", 409);
        }

        recordAction(incident, LaneIncidentActionType.MANUAL_OPEN, note, null, null, OUTCOME_BARRIER_OPENED);
        closeIncident(incident, LaneIncidentStatus.RESOLVED);

        ioTService.publishManualOpenCommand(incident.getBarrierId());
        if (incident.getDetectionPayload() != null) {
            smoothingAIEvents.markLaneCooldown(incident.getDetectionPayload());
        }
        log.info("manualOpen: incident id={} barrier={} opened manually", incident.getId(), incident.getBarrierId());
        return toResponse(incident, OUTCOME_BARRIER_OPENED);
    }

    /**
     * Admin từ chối: phiên này ko chính xác
     */
    @Transactional
    public LaneIncidentResponse reject(Long incidentId, String note) {
        LaneIncidentEntity incident = lockActiveIncident(incidentId, null);
        recordAction(incident, LaneIncidentActionType.REJECT, note, null, null, null);
        closeIncident(incident, LaneIncidentStatus.REJECTED);
        log.info("reject: incident id={} rejected", incident.getId());
        return toResponse(incident, null);
    }

    public void publishFaceMismatchConfirm(LaneIncidentEntity incident, ParkingSessionEntity session,
                                           VehicleEntity vehicle) {
        ioTService.publishLaneEvent(LaneEventCommand.builder()
                .event(LaneEventCommand.FACE_MISMATCH_CONFIRM)
                .incidentId(incident.getId())
                .laneId(incident.getLaneId())
                .barrierId(incident.getBarrierId())
                .directionType(DirectionType.EXIT)
                .plate(vehicle.getPlateNumber())
                .vehicleType(vehicle.getVehicleType())
                .profileId(incident.getExitProfileId())
                .sessionId(session.getId())
                .images(buildImages(session, incident))
                .eventTs(System.currentTimeMillis())
                .build());
    }

    public void publishPlateConfirmRequired(LaneIncidentEntity incident) {
        PendingEventDetection detection = incident.getDetectionPayload();
        ioTService.publishLaneEvent(LaneEventCommand.builder()
                .event(LaneEventCommand.PLATE_CONFIRM_REQUIRED)
                .incidentId(incident.getId())
                .laneId(incident.getLaneId())
                .barrierId(incident.getBarrierId())
                .directionType(DirectionType.EXIT)
                .plate(incident.getDetectedPlate())
                .vehicleType(detection != null ? detection.getVehicleType() : null)
                .profileId(incident.getExitProfileId())
                .images(buildImages(null, incident))
                .eventTs(System.currentTimeMillis())
                .build());
    }

    public List<LaneEventImage> buildImages(ParkingSessionEntity session, LaneIncidentEntity incident) {
        List<LaneEventImage> images = new ArrayList<>();
        if (session != null) {
            images.addAll(LaneEventImage.from("entry_", session.getEntryImageInfo()));
        }
        images.addAll(LaneEventImage.from("exit_", incident.getExitImageInfo()));
        return images;
    }

    private LaneIncidentEntity lockActiveIncident(Long incidentId, LaneIncidentType expectedType) {
        LaneIncidentEntity incident = laneIncidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy sự cố", "INCIDENT_NOT_FOUND", 404));
        if (!ACTIVE_STATUSES.contains(incident.getStatus())) {
            throw new BusinessException("Sự cố đã được xử lý", "INCIDENT_ALREADY_HANDLED", 409);
        }
        if (expectedType != null && incident.getType() != expectedType) {
            throw new BusinessException("Thao tác không áp dụng cho loại sự cố này", "INCIDENT_TYPE_INVALID", 400);
        }
        return incident;
    }

    private PendingEventDetection requireDetection(LaneIncidentEntity incident) {
        PendingEventDetection detection = incident.getDetectionPayload();
        if (detection == null) {
            throw new BusinessException("Sự cố thiếu dữ liệu nhận diện tại làn ra", "INCIDENT_PAYLOAD_MISSING", 400);
        }
        return detection;
    }

    private BarrierEntity requireBarrier(String barrierId) {
        BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                .orElse(null);
        if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
            throw new BusinessException("Cấu hình barrier/làn của lượt ra không hợp lệ", "BARRIER_CONFIG_INVALID", 400);
        }
        return barrier;
    }

    private ParkingCustomerEntity findExitCustomer(LaneIncidentEntity incident) {
        return incident.getExitProfileId() == null ? null
                : parkingCustomerRepository.findByAiProfileId(incident.getExitProfileId()).orElse(null);
    }

    private void recordAction(LaneIncidentEntity incident, LaneIncidentActionType actionType, String note,
                              String oldValue, String newValue, String result) {
        UserDTO actor = currentUser();

        LaneIncidentActionEntity action = new LaneIncidentActionEntity();
        action.setIncident(incident);
        action.setActionType(actionType);
        action.setActorUserId(actor != null ? actor.getUserId() : null);
        action.setActorUsername(actor != null ? actor.getUsername() : null);
        action.setNote(note);
        action.setOldValue(oldValue);
        action.setNewValue(newValue);
        action.setResult(result);
        laneIncidentActionRepository.save(action);
    }

    private void closeIncident(LaneIncidentEntity incident, LaneIncidentStatus status) {
        UserDTO actor = currentUser();
        incident.setStatus(status);
        incident.setResolvedAt(LocalDateTime.now());
        incident.setResolvedBy(actor != null ? actor.getUserId() : null);
        laneIncidentRepository.save(incident);
    }

    private UserDTO currentUser() {
        try {
            return UserContext.get();
        } catch (ServiceException e) {
            return null;
        }
    }

    private LaneIncidentResponse toResponse(LaneIncidentEntity incident, String outcome) {
        ParkingSessionEntity session = incident.getSession();
        return LaneIncidentResponse.builder()
                .id(incident.getId())
                .type(incident.getType())
                .status(incident.getStatus())
                .laneId(incident.getLaneId())
                .barrierId(incident.getBarrierId())
                .sessionId(session != null ? session.getId() : null)
                .plateNumber(incident.getDetectedPlate())
                .correctedPlate(incident.getCorrectedPlate())
                .entryProfileId(incident.getEntryProfileId())
                .exitProfileId(incident.getExitProfileId())
                .images(buildImages(session, incident))
                .outcome(outcome)
                .createdAt(incident.getCreatedAt())
                .resolvedAt(incident.getResolvedAt())
                .build();
    }
}
