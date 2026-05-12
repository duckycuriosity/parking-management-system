package vio.barie.core.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vio.barie.core.service.mqtt.DetectionMatchingService;

@Component
@RequiredArgsConstructor
public class AiEventBuffer {

    private final DetectionMatchingService detectionMatchingService;

    public void addFaceEvent(Long cameraId, Long laneId, Integer profileId) {
        detectionMatchingService.addFaceEvent(cameraId, laneId, profileId);
    }

    public void addPlateEvent(Long cameraId, Long laneId, String plateNumber, Integer aiPlateId) {
        detectionMatchingService.addPlateEvent(cameraId, laneId, plateNumber, aiPlateId);
    }
}
