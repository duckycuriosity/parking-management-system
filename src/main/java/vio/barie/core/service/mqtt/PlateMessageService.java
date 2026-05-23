package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlateMessageService {

    // setup logger with class name
    private static final Logger logger = LoggerFactory.getLogger(PlateMessageService.class);

    private final VehicleRepository vehicleRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;
    private final DetectionMatchingService detectionMatchingService;
    private final ParkingMessageService parkingMessageService;
    private final BarrierRepository barrierRepository;
    private final UUID uuid = UUID.randomUUID();

    public void handle(JsonNode event) {
        try {
            Long cameraCode = event.path("cameraId").asLong();
            String plateNumber = event.path("plate").path("plate").asText(null);
            String cameraUUID = event.path("cameraUUID").asText();
            Boolean isVerified = event.path("plate").path("isVerified").asBoolean(false);

            if (plateNumber == null || cameraUUID == null) {
                logger.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraCode);
                return;
            }

            CameraEntity aiCamera = cameraRepository.findCameraByUUID(cameraUUID).orElse(null);
            if (aiCamera == null) {
                logger.warn("Camera not found in AI system");
                parkingMessageService.publishErrorWhenHandlingEvent("Camera not found", cameraUUID, DetectionType.PLATE);
                return;
            }
            CameraEntity cameraInLane = cameraRepository.findByIdWithLane(aiCamera.getId()).orElse(null);
            if (cameraInLane == null) {
                logger.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
                parkingMessageService.publishErrorWhenHandlingEvent("Camera not found", cameraUUID, DetectionType.PLATE);
                return;
            }

            long laneId = cameraInLane.getLane().getId();
            logger.debug("PlateHandler: plate={} on laneId={}", plateNumber, laneId);

            Optional<BarrierEntity> barrier = barrierRepository.findBarrierByCameraIdAndRole(cameraInLane.getId(), DetectionType.PLATE.toString().toLowerCase());
            if (barrier.isEmpty()) {
                parkingMessageService.publishErrorWhenHandlingEvent("Camera does not match any barrier", cameraUUID, DetectionType.PLATE);
                logger.error("Camera does not match any barrier");
                return;
            }

            if (!vehicleRepository.existsByPlateNumber(plateNumber)) {
                VehicleEntity newVehicle = new VehicleEntity();
                newVehicle.setPlateNumber(plateNumber);
                newVehicle.setAiIdentityPlateId("UNVERIFIED_" + uuid.toString());
                newVehicle.setVehicleType("OTHER");
                vehicleRepository.save(newVehicle);
            }
            VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plateNumber);

            PendingEventDetection pd = new PendingEventDetection();
            pd.setType(DetectionType.PLATE);
            pd.setPlateNumber(plateNumber);

            if (barrier.map(BarrierEntity::getDirection).orElse("").equals(DirectionType.ENTRY.toString().toLowerCase())) {
                pd.setEntryLaneId(laneId);
                pd.setEntryBarrierId(barrier.map(BarrierEntity::getBarrierId).orElse(null));
                pd.setEntryCameraId(cameraInLane.getId());
                pd.setDirectionType(DirectionType.ENTRY);
                parkingMessageService.publishEntryVehicleInfo(barrier.map(BarrierEntity::getBarrierId).orElse(null), plateNumber, LocalDateTime.now());
            } else if (barrier.map(BarrierEntity::getDirection).orElse("").equals(DirectionType.EXIT.toString().toLowerCase())) {
                pd.setExitBarrierId(barrier.map(BarrierEntity::getBarrierId).orElse(null));
                pd.setExitCameraId(cameraInLane.getId());
                pd.setExitLaneId(laneId);
                pd.setDirectionType(DirectionType.EXIT);
            }
            pd.setTimestampMs(System.currentTimeMillis());

            aiEventBuffer.addPlateEvent(pd);
        } catch (Exception ex) {
            log.error("PlateMessageService: failed to handle plate event — payload={} error={}", event, ex.getMessage(),
                    ex);
        }
    }
}
