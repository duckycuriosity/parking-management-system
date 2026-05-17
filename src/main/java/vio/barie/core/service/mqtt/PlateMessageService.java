package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.VehicleRepository;

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
    private final UUID uuid = UUID.randomUUID();

    @Transactional()
    public void handle(JsonNode event) {
        Long cameraCode = event.path("cameraId").asLong();
        String plateNumber = event.path("plate").path("plate").asText(null);
        String cameraUUID = event.path("cameraUUID").asText();
        Boolean isVerified = event.path("plate").path("isVerified").asBoolean(false);

        if (plateNumber == null || cameraUUID == null) {
            logger.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraCode);
            // publish to hardware
            return;
        }

        CameraEntity aiCamera = cameraRepository.findCameraByUUID(cameraUUID).orElse(null);
        if (aiCamera == null) {
            logger.warn("Camera not found in AI system");
            return;
        }
        CameraEntity cameraInLane = cameraRepository.findByIdWithLane(aiCamera.getId()).orElse(null);
        if (cameraInLane == null) {
            logger.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
            return;
        }

        long laneId = cameraInLane.getLane().getId();
        logger.debug("PlateHandler: plate={} on laneId={}", plateNumber, laneId);

        if (!vehicleRepository.existsByPlateNumber(plateNumber)) {
            VehicleEntity newVehicle = new VehicleEntity();
            newVehicle.setPlateNumber(plateNumber);
            // Marked this vehicle is not registered in AI system
            newVehicle.setAiIdentityPlateId("UNVERIFIED_" + uuid.toString());
            newVehicle.setVehicleType("OTHER");
            vehicleRepository.save(newVehicle);
        }
        VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plateNumber);

        String barrierId = detectionMatchingService.resolveBarrierId(cameraInLane.getId(), "plate", laneId)
                .orElse(null);
        if (barrierId == null) {
            log.warn("PlateHandler: no barrier mapping for cameraId={} laneId={}", cameraInLane.getId(), laneId);
            return;
        }

        parkingMessageService.publishEntryVehicleInfo(barrierId, plateNumber, LocalDateTime.now());

        aiEventBuffer.addPlateEvent(cameraInLane.getId(), laneId, plateNumber, vehicle.getId().intValue());
    }
}
