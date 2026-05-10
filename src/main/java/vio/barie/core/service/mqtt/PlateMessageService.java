package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.websocket.AiEventBuffer;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlateMessageService {

    private final VehicleRepository vehicleRepository;
    private final CameraRepository cameraRepository;
    private final AiEventBuffer aiEventBuffer;

    public void handle(JsonNode event) {
        long cameraId = event.path("cameraId").asLong();
        String plateNumber = event.path("plateNumber").asText(null);
        String plateId = event.path("plateId").asText(null);

        if (plateId == null) {
            log.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraId);
            return;
        }

        CameraEntity camera = cameraRepository.findByIdWithLane(cameraId).orElse(null);
        if (camera == null) {
            log.warn("PlateHandler: unknown camera — cameraId={}", cameraId);
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

        aiEventBuffer.addPlateEvent(laneId, plateNumber, plateId);
    }
}
