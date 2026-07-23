package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.service.smoothing.SmoothingAIEvents;

@Component
@RequiredArgsConstructor
public class AiMQTTEventBuffer {

    private final DetectionMatchingService detectionMatchingService;
    private final SmoothingAIEvents smoothingAIEvents;

    public void addFaceEvent(PendingEventDetection pendingEvent) {
        smoothingAIEvents.ingestFace(pendingEvent).ifPresent(detectionMatchingService::emitMatch);
    }

    public void addPlateEvent(PendingEventDetection pendingEvent) {
        smoothingAIEvents.ingestPlate(pendingEvent);
    }
}
