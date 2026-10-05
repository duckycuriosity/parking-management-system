package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.LaneIncidentActionType;
import vio.barie.core.common.LaneIncidentStatus;
import vio.barie.core.common.LaneIncidentType;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.filter.UserContext;
import vio.barie.core.dto.LaneEventImage;
import vio.barie.core.dto.LaneIncidentResponse;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.dto.user.UserDTO;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.LaneIncidentActionEntity;
import vio.barie.core.entity.LaneIncidentEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.LaneIncidentActionRepository;
import vio.barie.core.repository.LaneIncidentRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.verification.ParkingSessionLifecycleService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LaneIncidentService {

    private final LaneIncidentRepository laneIncidentRepository;
    private final LaneIncidentActionRepository laneIncidentActionRepository;
    private final BarrierRepository barrierRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionLifecycleService sessionLifecycleService;

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

    @Transactional(readOnly = true)
    public List<LaneIncidentResponse> getPendingIncidents(Long laneId) {
        return laneIncidentRepository.findPendingWithOpenSession(laneId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Để admin tự xác nhận, nếu đúng thì tính payment như bình thường.
     */
    @Transactional
    public LaneIncidentResponse approve(Long incidentId, String note) {
        LaneIncidentEntity incident = lockPendingIncident(incidentId);

        ParkingSessionEntity session = incident.getSession();
        if (session == null || session.getExitTime() != null) {
            throw new BusinessException("Phiên gửi xe của sự cố này đã được đóng", "SESSION_ALREADY_CLOSED", 409);
        }

        PendingEventDetection detection = incident.getDetectionPayload();
        if (detection == null) {
            throw new BusinessException("Sự cố thiếu dữ liệu nhận diện tại làn ra", "INCIDENT_PAYLOAD_MISSING", 400);
        }

        BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(incident.getBarrierId())
                .orElse(null);
        if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
            throw new BusinessException("Cấu hình barrier/làn của lượt ra không hợp lệ", "BARRIER_CONFIG_INVALID", 400);
        }

        ParkingCustomerEntity exitCustomer = incident.getExitProfileId() == null ? null
                : parkingCustomerRepository.findByAiProfileId(incident.getExitProfileId()).orElse(null);

        closeIncident(incident, LaneIncidentStatus.RESOLVED, LaneIncidentActionType.APPROVE_FACE, note);
        LaneIncidentResponse response = toResponse(incident);

        sessionLifecycleService.closeExit(session, exitCustomer, barrier, detection);
        log.info("approve: incident id={} approved, session id={} closed", incident.getId(), session.getId());
        return response;
    }

    /**
     * Admin từ chối: phiên này ko chính xác
     */
    @Transactional
    public LaneIncidentResponse reject(Long incidentId, String note) {
        LaneIncidentEntity incident = lockPendingIncident(incidentId);
        closeIncident(incident, LaneIncidentStatus.REJECTED, LaneIncidentActionType.REJECT, note);
        log.info("reject: incident id={} rejected", incident.getId());
        return toResponse(incident);
    }

    public List<LaneEventImage> buildImages(ParkingSessionEntity session, LaneIncidentEntity incident) {
        List<LaneEventImage> images = new ArrayList<>();
        if (session != null) {
            images.addAll(LaneEventImage.from("entry_", session.getEntryImageInfo()));
        }
        images.addAll(LaneEventImage.from("exit_", incident.getExitImageInfo()));
        return images;
    }

    private LaneIncidentEntity lockPendingIncident(Long incidentId) {
        LaneIncidentEntity incident = laneIncidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy sự cố", "INCIDENT_NOT_FOUND", 404));
        if (incident.getStatus() != LaneIncidentStatus.PENDING) {
            throw new BusinessException("Sự cố đã được xử lý", "INCIDENT_ALREADY_HANDLED", 409);
        }
        return incident;
    }

    private void closeIncident(LaneIncidentEntity incident, LaneIncidentStatus status,
                               LaneIncidentActionType actionType, String note) {
        UserDTO actor = currentUser();

        LaneIncidentActionEntity action = new LaneIncidentActionEntity();
        action.setIncident(incident);
        action.setActionType(actionType);
        action.setActorUserId(actor != null ? actor.getUserId() : null);
        action.setActorUsername(actor != null ? actor.getUsername() : null);
        action.setNote(note);
        laneIncidentActionRepository.save(action);

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

    private LaneIncidentResponse toResponse(LaneIncidentEntity incident) {
        ParkingSessionEntity session = incident.getSession();
        return LaneIncidentResponse.builder()
                .id(incident.getId())
                .type(incident.getType())
                .status(incident.getStatus())
                .laneId(incident.getLaneId())
                .barrierId(incident.getBarrierId())
                .sessionId(session != null ? session.getId() : null)
                .plateNumber(incident.getDetectedPlate())
                .entryProfileId(incident.getEntryProfileId())
                .exitProfileId(incident.getExitProfileId())
                .images(buildImages(session, incident))
                .createdAt(incident.getCreatedAt())
                .resolvedAt(incident.getResolvedAt())
                .build();
    }
}
