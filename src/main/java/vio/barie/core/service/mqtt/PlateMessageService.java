package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final VehicleRepository vehicleRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;
    private final ParkingMessageService parkingMessageService;
    UUID uuid = UUID.randomUUID();

    // todo: tạm thời fix cứng
    private final String device = "pk_df102790fdca47ad";

    @Transactional()
    public void handle(JsonNode event) {
        Long cameraCode = event.path("cameraId").asLong();
        String plateNumber = event.path("plate").path("plate").asText(null);
        Boolean isVerified = event.path("plate").path("isVerified").asBoolean(false);

        if (plateNumber == null) {
            log.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraCode);
            // publish to hardware
            return;
        }

        CameraEntity aiCamera = cameraRepository.findCameraByAiCameraId(cameraCode).orElse(null);
        if (aiCamera == null) {
            log.warn("Camera not found in AI system");
            return;
        }
        CameraEntity cameraInLane = cameraRepository.findByCodeWithLane(aiCamera.getId().toString()).orElse(null);
        if (cameraInLane == null) {
            log.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
            return;
        }

        long laneId = cameraInLane.getLane().getId();
        log.debug("PlateHandler: plate={} on laneId={}", plateNumber, laneId);

        if(!vehicleRepository.existsByPlateNumber(plateNumber)){
            VehicleEntity newVehicle = new VehicleEntity();
            newVehicle.setPlateNumber(plateNumber);
            // Marked this vehicle is not registered in AI system
            newVehicle.setAiIdentityPlateId("UNVERIFIED_" + uuid.toString());
            newVehicle.setVehicleType("OTHER");
            vehicleRepository.save(newVehicle);
        }
        VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plateNumber);

        parkingMessageService.publishEntryVehicleInfo(device,plateNumber, LocalDateTime.now());

        aiEventBuffer.addPlateEvent(laneId, plateNumber, vehicle.getId().intValue());
    }
}
