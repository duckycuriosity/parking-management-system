package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.dto.LaneVehicleCommand;
import vio.barie.core.dto.parking.EventImageInfo;
import vio.barie.core.dto.parking.ImageDetail;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.filter.AiEventQualityFilter;
import vio.barie.core.service.filter.QualityCheckResult;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.utils.PlateNumberUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import vio.barie.core.utils.VehicleType;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlateMessageService {

    // setup logger with class name
    private static final Logger logger = LoggerFactory.getLogger(PlateMessageService.class);

    private final VehicleRepository vehicleRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;
    private final TicketRepository ticketRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingMessageService parkingMessageService;
    private final BarrierRepository barrierRepository;
    private final DetectionMatchingService detectionMatchingService;
    private final AiEventQualityFilter aiEventQualityFilter;
    private final UUID uuid = UUID.randomUUID();

    @Transactional
    public void handle(JsonNode event) {
        try {
            Long cameraCode = event.path("cameraId").asLong();
            String plateNumber = PlateNumberUtils.normalize(event.path("plate").path("plateNumber").asText(null));
            String cameraUUID = event.path("cameraUUID").asText();

            if (plateNumber == null || cameraUUID == null || plateNumber.isBlank()) {
                logger.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraCode);
                parkingMessageService.publishErrorWhenHandlingEvent("Plate is not valid", cameraUUID,
                        DetectionType.PLATE);
                return;
            }

            CameraEntity aiCamera = cameraRepository.findCameraByUUID(cameraUUID).orElse(null);
            if (aiCamera == null) {
                logger.warn("Camera not found in AI system");
                parkingMessageService.publishErrorWhenHandlingEvent("Camera not found", cameraUUID,
                        DetectionType.PLATE);
                return;
            }
            CameraEntity cameraInLane = cameraRepository.findByIdWithLane(aiCamera.getId()).orElse(null);
            if (cameraInLane == null) {
                logger.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
                parkingMessageService.publishErrorWhenHandlingEvent("Camera not found", cameraUUID,
                        DetectionType.PLATE);
                return;
            }

            long laneId = cameraInLane.getLane().getId();
            logger.debug("PlateHandler: plate={} on laneId={}", plateNumber, laneId);

            Optional<BarrierEntity> barrier = barrierRepository.findBarrierByCameraIdAndRole(cameraInLane.getId(),
                    DetectionType.PLATE.toString().toLowerCase());
            if (barrier.isEmpty()) {
                parkingMessageService.publishErrorWhenHandlingEvent("Camera does not match any barrier", cameraUUID,
                        DetectionType.PLATE);
                logger.error("Camera does not match any barrier");
                return;
            }

            if (!PlateNumberUtils.isValid(plateNumber)) {
                logger.warn("PlateHandler: plate format invalid — plate={} cameraUUID={}", plateNumber, cameraUUID);
                parkingMessageService.publishDisplayMsg(barrier.get().getBarrierId(),
                        "Bien so khong hop le, moi quet lai", true, "Vui long quet lai bien so", 8);
                return;
            }

            QualityCheckResult qualityCheckResult = aiEventQualityFilter.evaluatePlate(event);

            if (!qualityCheckResult.accepted()) {
                logger.debug("PlateHandler: plate event rejected - reason={} value ={} cameraUUID={}", qualityCheckResult.reason(), qualityCheckResult.value(), cameraUUID);
                parkingMessageService.publishErrorWhenHandlingEvent("Chua nhan dien duoc bien so", cameraUUID,
                        DetectionType.PLATE, barrier.get().getBarrierId(), plateNumber, null);
                return;
            }

            Boolean isVerified = event.path("plate").path("isVerified").asBoolean(false);
            Integer labelTypeVehicle = event.path("object").path("label").asInt();
            Double confidence = event.path("plate").path("confidence").asDouble();

            DirectionType direction = barrier.get().getDirection();

            EventImageInfo plateImgInfo = extractImageInfo(event, "plate");
            if (plateImgInfo != null) {
                log.info(">>> THÀNH CÔNG PARSE JSON ẢNH BIẾN SỐ: {}", plateImgInfo);
            } else {
                log.error(">>> LỖI PARSE JSON: Không tìm thấy trường image/cropImage trong payload!");
            }
            VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plateNumber);
            String vehicleTypeStr = labelTypeVehicle.equals(VehicleType.CAR.getCode()) ? "CAR" : "MOTORBIKE";
            parkingMessageService.publishLaneVehicle(LaneVehicleCommand.builder()
                    .laneId(laneId)
                    .barrierId(barrier.get().getBarrierId())
                    .directionType(direction)
                    .state("WAITING_FACE")
                    .plate(plateNumber)
                    .aiPlateId(vehicle != null ? vehicle.getAiIdentityPlateId() : null)
                    .confidence(confidence)
                    .isVerified(isVerified)
                    .vehicleType(vehicleTypeStr)
                    .eventTs(event.path("eventTime").isMissingNode() ?
                        System.currentTimeMillis() : Instant.parse(event.path("eventTime").asText()).toEpochMilli())
                    .image(plateImgInfo != null ? plateImgInfo.getImageInfo().getFirst().getName() : "")
                .build());

            // If vehicle is registered with active ticket, handle as registered customer
            if (vehicle != null) {
                TicketEntity ticket = ticketRepository.findByVehicleIdAndStatus(vehicle.getId(), TicketStatus.ACTIVE).orElse(null);
                if (ticket != null) {
                    handleRegisteredVehicle(ticket, vehicle, plateNumber, cameraInLane, barrier.get(), laneId, direction, cameraUUID, plateImgInfo, confidence);
                    return;
                }
            }


            // Otherwise handle as guest/unregistered vehicle
            handleUnregisteredVehicle(plateNumber, cameraInLane, barrier.get(), laneId, direction, labelTypeVehicle, plateImgInfo, confidence);
        } catch (Exception ex) {
            log.error("PlateMessageService: failed to handle plate event — payload={} error={}", event, ex.getMessage(),
                    ex);
        }
    }

    /**
     * Handle plate event for registered customer (has active ticket)
     * For ENTRY: immediately process without waiting for face match
     * For EXIT: buffer event and wait for face match to verify identity
     */
    private void handleRegisteredVehicle(TicketEntity ticket, VehicleEntity vehicle, String plateNumber,
                                         CameraEntity cameraInLane, BarrierEntity barrier,
                                         long laneId, DirectionType direction, String cameraUUID, EventImageInfo plateImgInfo,
                                         Double confidence) {
        try {
            ParkingCustomerEntity customer = ticket.getCustomer();

            if (customer == null) {
                parkingMessageService.publishErrorWhenHandlingEvent(
                        "Customer not found for ticket", cameraUUID, DetectionType.PLATE,
                        barrier.getBarrierId());
                logger.warn("Customer not found for vehicle: plate={}", plateNumber);
                return;
            }

            PendingEventDetection event = buildPendingEventForRegistered(
                    vehicle, customer, plateNumber, cameraInLane, barrier, laneId, direction, plateImgInfo, confidence);
            logger.info("{} registered vehicle plate={} customer={} — buffering for face match to get full images",
                    direction, plateNumber, customer.getId());
            aiEventBuffer.addPlateEvent(event);

            // hiện tại lượt entry đang nhảy cóc bypass k cho ghép face dẫn đến thiếu ảnh, giờ bắt buộc phải vào buffer ghép mặt giống khách vãng lai
//            if (direction == DirectionType.ENTRY) {
//                // For registered customers at entry: immediately process without face matching.
//                logger.info("ENTRY registered vehicle plate={} customer={} — processing immediately",
//                        plateNumber, customer.getId());
//                detectionMatchingService.handleEntryEvent(event);
//            } else if (direction == DirectionType.EXIT) {
//                // For registered customers at exit: buffer event and wait for face match
//                // to verify it's the same person who entered
//                logger.info("EXIT registered vehicle plate={} customer={} — buffering for face match",
//                        plateNumber, customer.getId());
//                aiEventBuffer.addPlateEvent(event);
//            } else {
//                parkingMessageService.publishErrorWhenHandlingEvent(
//                        "Unknown direction type for plate event", cameraUUID, DetectionType.PLATE,
//                        barrier.getBarrierId());
//                logger.warn("Unknown direction type for plate event: plate={} direction={}", plateNumber, direction);
//            }

        } catch (Exception ex) {
            parkingMessageService.publishErrorWhenHandlingEvent(
                    "Failed to process registered vehicle event", cameraUUID, DetectionType.PLATE,
                    barrier.getBarrierId());
            logger.error("handleRegisteredVehicle: failed to process registered vehicle — plate={} error={}",
                    plateNumber, ex.getMessage(), ex);
        }
    }

    /**
     * Handle plate event for unregistered/guest vehicle
     * Always buffer event and wait for face matching logic to create session
     */
    private void handleUnregisteredVehicle(String plateNumber, CameraEntity cameraInLane,
                                           BarrierEntity barrier, long laneId, DirectionType direction, Integer vehicleType, EventImageInfo plateImgInfo,
                                           Double confidence) {
        try {
            if (!vehicleRepository.existsByPlateNumber(plateNumber)) {
                logger.info("Vehicle with plate={} exists but no active ticket — treating as unregistered",
                        plateNumber);
                VehicleEntity newVehicle = createNewVehicle(plateNumber, vehicleType);
            }

            PendingEventDetection event = buildPendingEventForUnregistered(
                    plateNumber, cameraInLane, barrier, laneId, direction, plateImgInfo, confidence);

            if (direction == DirectionType.ENTRY) {
                logger.info("ENTRY unregistered vehicle plate={} — buffering for face + plate matching", plateNumber);
            } else if (direction == DirectionType.EXIT) {
                logger.info("EXIT unregistered vehicle plate={} — buffering for face + plate matching", plateNumber);
            }

            aiEventBuffer.addPlateEvent(event);

        } catch (Exception ex) {
            parkingMessageService.publishErrorWhenHandlingEvent(
                    "Failed to process unregistered vehicle event", cameraInLane.getUuid(), DetectionType.PLATE,
                    barrier.getBarrierId());
            logger.error("handleUnregisteredVehicle: failed to process unregistered vehicle — plate={} error={}",
                    plateNumber, ex.getMessage(), ex);
        }
    }

    /**
     * Create PendingEventDetection for registered customer (has ticket)
     */
    private PendingEventDetection buildPendingEventForRegistered(VehicleEntity vehicle,
                                                                 ParkingCustomerEntity customer,
                                                                 String plateNumber,
                                                                 CameraEntity camera,
                                                                 BarrierEntity barrier,
                                                                 long laneId,
                                                                 DirectionType direction,
                                                                 EventImageInfo plateImgInfo,
                                                                 Double confidence) {
        PendingEventDetection event = new PendingEventDetection();
        event.setPlateNumber(plateNumber);
        // aiIdentityPlateId can be a numeric id or UUID string from AI; keep as String
        event.setAiPlateId(vehicle.getAiIdentityPlateId());
        event.setType(DetectionType.PLATE);
        event.setTimestampMs(System.currentTimeMillis());
        event.setProfileId(customer.getAiProfileId());
        event.setDirectionType(direction);
        event.setConfidence(confidence);

        if (direction == DirectionType.ENTRY) {
            event.setEntryBarrierId(barrier.getBarrierId());
            event.setEntryCameraId(camera.getId());
            event.setEntryLaneId(laneId);
        } else {
            event.setExitBarrierId(barrier.getBarrierId());
            event.setExitCameraId(camera.getId());
            event.setExitLaneId(laneId);
        }
        if (plateImgInfo != null) {
            event.getListImageInfo().add(plateImgInfo);
        }
        return event;
    }

    /**
     * Create PendingEventDetection for unregistered vehicle (guest)
     */
    private PendingEventDetection buildPendingEventForUnregistered(String plateNumber,
                                                                   CameraEntity camera,
                                                                   BarrierEntity barrier,
                                                                   long laneId,
                                                                   DirectionType direction,
                                                                   EventImageInfo plateImgInfo,
                                                                   Double confidence) {
        PendingEventDetection event = new PendingEventDetection();
        event.setType(DetectionType.PLATE);
        event.setPlateNumber(plateNumber);
        event.setTimestampMs(System.currentTimeMillis());
        event.setDirectionType(direction);
        event.setConfidence(confidence);

        if (direction == DirectionType.ENTRY) {
            event.setEntryBarrierId(barrier.getBarrierId());
            event.setEntryCameraId(camera.getId());
            event.setEntryLaneId(laneId);
        } else {
            event.setExitBarrierId(barrier.getBarrierId());
            event.setExitCameraId(camera.getId());
            event.setExitLaneId(laneId);
        }
        if (plateImgInfo != null) {
            event.getListImageInfo().add(plateImgInfo);
        }
        return event;
    }

    /**
     * Create new vehicle record for unregistered vehicle with temporary AI identity
     */
    private VehicleEntity createNewVehicle(String plateNumber, Integer vehicleType) {
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setPlateNumber(plateNumber);
        vehicle.setAiIdentityPlateId("UNVERIFIED_" + UUID.randomUUID().toString());
        if (vehicleType.equals(VehicleType.CAR.getCode())) {
            vehicle.setVehicleType("CAR");
        } else {
            vehicle.setVehicleType("MOTORBIKE");
        }
        try {
            return vehicleRepository.save(vehicle);
        } catch (DataIntegrityViolationException ex) {
            // Likely a concurrent insert created the same plate; fetch and return existing
            logger.warn("createNewVehicle: concurrent insert detected for plate={}, fetching existing", plateNumber);
            VehicleEntity existing = vehicleRepository.findPlateByPlateNumber(plateNumber);
            if (existing != null) {
                return existing;
            }
            // rethrow if we cannot recover
            throw ex;
        }
    }

    private EventImageInfo extractImageInfo(JsonNode event, String evenType) {
        JsonNode images = event.path("images");
        if (images.isMissingNode()) {
            return null;
        }
        String timestamp = event.path("eventTime").asText(Instant.now().toString());
        List<ImageDetail> details = new ArrayList<>();

        String fullFrameUrl = images.path("fullFrame").path("accessUrl").asText(null);
        if (fullFrameUrl != null) {
            details.add(new ImageDetail(fullFrameUrl, timestamp, "root"));
        }
        String plateCropUrl = images.path("plateCrop").path("accessUrl").asText(null);
        if (plateCropUrl != null) {
            details.add(new ImageDetail(plateCropUrl, timestamp, "crop"));
        }
        if (details.isEmpty()) {
            return null;
        }
        EventImageInfo info = new EventImageInfo();
        info.setEventType(evenType);
        info.setImageInfo(details);
        return info;
    }

}
