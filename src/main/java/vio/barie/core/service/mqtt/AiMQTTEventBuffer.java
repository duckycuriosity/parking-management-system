package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vio.barie.core.dto.parking.PendingEventDetection;

@Component
@RequiredArgsConstructor
public class AiMQTTEventBuffer {

    private final DetectionMatchingService detectionMatchingService;

    public void addFaceEvent(PendingEventDetection pendingEvent) {
        detectionMatchingService.ingest(pendingEvent);
    }

    public void addPlateEvent(PendingEventDetection pendingEvent) {
        detectionMatchingService.ingest(pendingEvent);
    }
}
