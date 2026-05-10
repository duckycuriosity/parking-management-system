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

@Slf4j
@Component
@RequiredArgsConstructor
public class PlateMessageService {

    private final VehicleRepository vehicleRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;
    private final ParkingMessageService parkingMessageService;

    // todo: tạm thời fix cứng
    private final String device = "pk_df102790fdca47ad";

    @Transactional()
    public void handle(JsonNode event) {
        String cameraCode = event.path("cameraId").asText();
        String plateNumber = event.path("plateNumber").asText(null);
        String plateId = event.path("plateId").asText(null);

        if (plateId == null) {
            log.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraCode);
            return;
        }

        CameraEntity camera = cameraRepository.findByCodeWithLane(cameraCode).orElse(null);
        if (camera == null) {
            log.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
            return;
        }

        long laneId = camera.getLane().getId();
        log.debug("PlateHandler: plate={} on laneId={}", plateNumber, laneId);

        if(vehicleRepository.findByAiIdentityPlateId(plateId).isEmpty()){
            VehicleEntity newVehicle = new VehicleEntity();
            newVehicle.setPlateNumber(plateNumber);
            newVehicle.setAiIdentityPlateId(plateId);
            vehicleRepository.save(newVehicle);
        }

        parkingMessageService.publishEntryVehicleInfo(device,plateNumber, LocalDateTime.now());

        aiEventBuffer.addPlateEvent(laneId, plateNumber, plateId);
    }
}
