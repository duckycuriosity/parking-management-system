package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.parking.ParkingEvent;
import vio.barie.core.entity.BarrierCameraEntity;
import vio.barie.core.repository.BarrierCameraRepository;
import vio.barie.core.service.ParkingService;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionMatchingService {

    private static final long TIMEOUT_MS = 300_0000L;
    private static final long MATCH_WINDOW_MS = 180_000L;

    private final ParkingService parkingService;
    private final BarrierCameraRepository barrierCameraRepository;
    private final ConcurrentHashMap<String, BarrierBuffer> buffers = new ConcurrentHashMap<>();

    public Optional<String> resolveBarrierId(Long cameraId, String role) {
        if (cameraId == null) {
            return Optional.empty();
        }

        List<BarrierCameraEntity> mappings = barrierCameraRepository.findByCameraIdAndRoleIgnoreCase(cameraId, role);
        if (mappings.isEmpty()) {
            mappings = barrierCameraRepository.findByCameraId(cameraId);
        }

        return mappings.stream()
                .filter(m -> m.getBarrierId() != null && !m.getBarrierId().isBlank())
                .sorted(Comparator.comparingInt((BarrierCameraEntity m) -> m.getPriority() != null ? m.getPriority() : 0).reversed())
                .map(BarrierCameraEntity::getBarrierId)
                .findFirst();
    }

    public void addFaceEvent(Long cameraId, Long laneId, Integer profileId) {
        String barrierId = resolveBarrierId(cameraId, "face").orElse(null);
        if (barrierId == null) {
            log.warn("DetectionMatchingService: no barrier mapping for face event — cameraId={}, laneId={}, profileId={}", cameraId, laneId, profileId);
            return;
        }

        ingest(new PendingDetection(barrierId, cameraId, laneId, DetectionType.FACE, profileId, null, null, System.currentTimeMillis()));
    }

    public void addPlateEvent(Long cameraId, Long laneId, String plateNumber, Integer aiPlateId) {
        String barrierId = resolveBarrierId(cameraId, "plate").orElse(null);
        if (barrierId == null) {
            log.warn("DetectionMatchingService: no barrier mapping for plate event — cameraId={}, laneId={}, plate={}", cameraId, laneId, plateNumber);
            return;
        }

        ingest(new PendingDetection(barrierId, cameraId, laneId, DetectionType.PLATE, null, plateNumber, aiPlateId, System.currentTimeMillis()));
    }

    private void ingest(PendingDetection detection) {
        BarrierBuffer buffer = buffers.computeIfAbsent(detection.barrierId(), ignored -> new BarrierBuffer());

        buffer.lock().lock();
        try {
            if (detection.type() == DetectionType.FACE) {
                PendingDetection matched = pollMatching(buffer.plates(), detection);
                if (matched != null) {
                    emitMatch(detection, matched);
                    return;
                }
                buffer.faces().addLast(detection);
            } else {
                PendingDetection matched = pollMatching(buffer.faces(), detection);
                if (matched != null) {
                    emitMatch(matched, detection);
                    return;
                }
                buffer.plates().addLast(detection);
            }
        } finally {
            buffer.lock().unlock();
        }
    }

    private PendingDetection pollMatching(Deque<PendingDetection> candidates, PendingDetection incoming) {
        PendingDetection best = null;
        long bestDelta = Long.MAX_VALUE;
        Iterator<PendingDetection> iterator = candidates.iterator();
        while (iterator.hasNext()) {
            PendingDetection candidate = iterator.next();
            if (isExpired(candidate)) {
                iterator.remove();
                continue;
            }

            long delta = Math.abs(incoming.timestampMs() - candidate.timestampMs());
            if (delta <= MATCH_WINDOW_MS && delta < bestDelta) {
                best = candidate;
                bestDelta = delta;
            }
        }

        if (best != null) {
            candidates.remove(best);
        }
        return best;
    }

    private void emitMatch(PendingDetection face, PendingDetection plate) {
        try {
            ParkingEvent event = new ParkingEvent();
            event.setPlateNumber(plate.plateNumber());
            event.setAiIdentityPlateId(plate.aiPlateId());
            event.setAiIdentityCustomerId(face.profileId());
            event.setLaneId(face.laneId() != null ? face.laneId() : plate.laneId());
            event.setBarrierId(face.barrierId());

            parkingService.handle(event);
            log.info("DetectionMatchingService: matched barrier={} laneId={} plate={} profileId={}",
                    face.barrierId(), event.getLaneId(), plate.plateNumber(), face.profileId());
        } catch (Exception ex) {
            log.error("DetectionMatchingService: handle event failed for barrier={} laneId={}: {}",
                    face.barrierId(), face.laneId(), ex.getMessage(), ex);
        }
    }

    @Scheduled(fixedDelay = 3_000)
    void cleanupExpired() {
        long now = System.currentTimeMillis();
        buffers.forEach((barrierId, buffer) -> {
            if (!buffer.lock().tryLock()) {
                return;
            }

            try {
                buffer.faces().removeIf(d -> now - d.timestampMs() > TIMEOUT_MS);
                buffer.plates().removeIf(d -> now - d.timestampMs() > TIMEOUT_MS);

                if (buffer.faces().isEmpty() && buffer.plates().isEmpty()) {
                    buffers.remove(barrierId, buffer);
                }
            } finally {
                buffer.lock().unlock();
            }
        });
    }

    private boolean isExpired(PendingDetection detection) {
        return System.currentTimeMillis() - detection.timestampMs() > TIMEOUT_MS;
    }

    private record PendingDetection(
            String barrierId,
            Long cameraId,
            Long laneId,
            DetectionType type,
            Integer profileId,
            String plateNumber,
            Integer aiPlateId,
            long timestampMs) {
    }

    private enum DetectionType {
        FACE,
        PLATE
    }

    private static final class BarrierBuffer {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<PendingDetection> faces = new ArrayDeque<>();
        private final Deque<PendingDetection> plates = new ArrayDeque<>();

        private ReentrantLock lock() {
            return lock;
        }

        private Deque<PendingDetection> faces() {
            return faces;
        }

        private Deque<PendingDetection> plates() {
            return plates;
        }
    }
}