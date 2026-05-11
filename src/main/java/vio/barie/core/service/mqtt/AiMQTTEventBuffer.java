package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vio.barie.core.dto.parking.ParkingEvent;
import vio.barie.core.service.ParkingService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

// buffer class để lưu các event đến từ AI , sau đó merge chúng để tạo thành một Parking Event hoàn chỉnh
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMQTTEventBuffer {

    private static final long TIMEOUT_MS = 60_000L;

    private final ParkingService parkingService;
    private final ConcurrentHashMap<Long, PendingLaneEvent> buffer = new ConcurrentHashMap<>();

    public void addFaceEvent(long laneId, Integer profileId) {
        AtomicReference<PendingPlateEvent> matched = new AtomicReference<>();

        buffer.compute(laneId, (id, existing) -> {
            if (existing != null && existing.plate != null && !existing.plate.isExpired(TIMEOUT_MS)) {
                matched.set(existing.plate);
                return null;
            }
            PendingLaneEvent next = existing != null ? existing : new PendingLaneEvent();
            next.face = new PendingFaceEvent(profileId, System.currentTimeMillis());
            return next;
        });

        PendingPlateEvent plate = matched.get();
        if (plate != null) {
            trigger(plate.plateNumber(), plate.aiPlateId(), profileId, laneId);
        }
    }

    public void addPlateEvent(long laneId, String plateNumber, String aiPlateId) {
        AtomicReference<PendingFaceEvent> matched = new AtomicReference<>();

        buffer.compute(laneId, (id, existing) -> {
            if (existing != null && existing.face != null && !existing.face.isExpired(TIMEOUT_MS)) {
                matched.set(existing.face);
                return null;
            }
            PendingLaneEvent next = existing != null ? existing : new PendingLaneEvent();
            next.plate = new PendingPlateEvent(plateNumber, aiPlateId, System.currentTimeMillis());
            return next;
        });

        PendingFaceEvent face = matched.get();
        if (face != null) {
            trigger(plateNumber, aiPlateId, face.profileId(), laneId);
        }
    }

    private void trigger(String plateNumber, String aiPlateId, Integer profileId, long laneId) {
        try {
            ParkingEvent event = new ParkingEvent();
            event.setPlateNumber(plateNumber);
            event.setAiIdentityPlateId(aiPlateId);
            event.setAiIdentityCustomerId(profileId);
            event.setLaneId(laneId);
            parkingService.handle(event);
            log.info("AiEventBuffer: matched — laneId={}, plate={}, profileId={}", laneId, plateNumber, profileId);
        } catch (Exception ex) {
            log.error("AiEventBuffer: handle event failed for laneId={}: {}", laneId, ex.getMessage(), ex);
        }
    }

    @Scheduled(fixedDelay = 3_000)
    void cleanupExpired() {
        buffer.forEach((laneId, pending) ->
            buffer.compute(laneId, (id, p) -> {
                if (p == null) return null;
                if (p.face != null && p.face.isExpired(TIMEOUT_MS)) {
                    log.warn("AiEventBuffer: face event timed out — laneId={}, profileId={}", id, p.face.profileId());
                    p.face = null;
                }
                if (p.plate != null && p.plate.isExpired(TIMEOUT_MS)) {
                    log.warn("AiEventBuffer: plate event timed out — laneId={}, plate={}", id, p.plate.plateNumber());
                    p.plate = null;
                }
                return (p.face == null && p.plate == null) ? null : p;
            })
        );
    }

    static class PendingLaneEvent {
        volatile PendingFaceEvent face;
        volatile PendingPlateEvent plate;
    }

    record PendingFaceEvent(Integer profileId, long timestamp) {
        boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }
    }

    record PendingPlateEvent(String plateNumber, String aiPlateId, long timestamp) {
        boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }
    }
}
