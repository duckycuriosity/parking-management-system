package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.parking.ParkingEvent;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.ParkingService;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionMatchingService {

    private static final Logger logger = LoggerFactory.getLogger(DetectionMatchingService.class);

    private static final long TIMEOUT_MS = 300_0000L;
    private static final long MATCH_WINDOW_MS = 180_000L;

    private final ParkingService parkingService;
    private final BarrierCameraRepository barrierCameraRepository;
    private final BarrierRepository barrierRepository;
    private final TicketRepository ticketRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ConcurrentHashMap<String, BarrierBuffer> buffers = new ConcurrentHashMap<>();

    public Optional<String> resolveBarrierId(Long cameraId, String role, Long laneId) {
        if (cameraId == null || laneId == null) {
            return Optional.empty();
        }

        // Exact match: camera_id + role (each camera maps to one barrier with one role)
        List<BarrierCameraEntity> mappings = barrierCameraRepository.findByCameraIdAndRoleIgnoreCase(cameraId, role);
        if (mappings.isEmpty()) {
            log.debug("resolveBarrierId: no mapping found for cameraId={} role={}", cameraId, role);
            return Optional.empty();
        }

        // Take first mapping (should be exactly 1)
        String barrierId = mappings.get(0).getBarrierId();
        if (barrierId == null || barrierId.isBlank()) {
            return Optional.empty();
        }

        // Validate lane: fetch barrier and check its lane matches laneId
        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId).orElse(null);
        if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
            log.warn("resolveBarrierId: barrier {} has no lane configured", barrierId);
            return Optional.empty();
        }

        Long barrierLaneId = barrier.getParkingDevice().getLane().getId();
        if (!barrierLaneId.equals(laneId)) {
            log.warn("resolveBarrierId: lane mismatch — barrier {} is in lane {}, but received event from lane {}",
                    barrierId, barrierLaneId, laneId);
            return Optional.empty();
        }

        return Optional.of(barrierId);
    }

    public void addFaceEvent(Long cameraId, Long laneId, Integer profileId) {
        String barrierId = resolveBarrierId(cameraId, "face", laneId).orElse(null);
        if (barrierId == null) {
            log.warn(
                    "DetectionMatchingService: no barrier mapping for face event — cameraId={}, laneId={}, profileId={}",
                    cameraId, laneId, profileId);
            return;
        }

        ingest(new PendingDetection(barrierId, cameraId, laneId, DetectionType.FACE, profileId, null, null,
                System.currentTimeMillis()));
    }

    public void addPlateEvent(Long cameraId, Long laneId, String plateNumber, Integer aiPlateId) {
        String barrierId = resolveBarrierId(cameraId, "plate", laneId).orElse(null);
        if (barrierId == null) {
            log.warn("DetectionMatchingService: no barrier mapping for plate event — cameraId={}, laneId={}, plate={}",
                    cameraId, laneId, plateNumber);
            return;
        }

        ingest(new PendingDetection(barrierId, cameraId, laneId, DetectionType.PLATE, null, plateNumber, aiPlateId,
                System.currentTimeMillis()));
    }

    private void ingest(PendingDetection detection) {
        BarrierBuffer buffer = buffers.computeIfAbsent(detection.barrierId(), ignored -> new BarrierBuffer());

        buffer.lock().lock();
        try {
            if (detection.type() == DetectionType.FACE) {
                PendingDetection matched = pollMatching(buffer.plates(), detection);
                // We need to detect the event from face camera and plate license camera are all
                // in relation with barrierId, laneId (if any), and timestamp within a short
                // window.
                // So we can not emit event immediately when receive plate detection without
                // waiting for face detection, and vice versa.
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
            VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plate.plateNumber);
            if (vehicle == null) {
                logger.error("Cannot find vehicle");
                return;
            }

            ParkingCustomerEntity customer = parkingCustomerRepository.findByAiProfileId(face.profileId)
                    .orElseThrow(() -> new ServiceException("Không tìm thấy thông tin khách hàng từ profile AI", 404));

            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(face.barrierId())
                    .orElseThrow(() -> new ServiceException("Không tìm thấy barrier", 404));
            LaneEntity lane = barrier.getParkingDevice().getLane();
            if (lane == null || lane.getParkingLot() == null) {
                throw new ServiceException("Barrier chưa cấu hình lane/parking_lot", 400);
            }

            String direction = barrier.getDirection() == null ? "" : barrier.getDirection().trim().toLowerCase();
            if ("entry".equals(direction)) {
                Optional<TicketEntity> ticket = ticketRepository
                        .findTicketByCustomerIdAndVehicleId(customer.getId(), vehicle.getId())
                        .filter(t -> "ACTIVE".equalsIgnoreCase(t.getStatus()))
                        .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()));

                ParkingSessionEntity session = new ParkingSessionEntity();
                session.setVehicle(vehicle);
                session.setEntryCustomer(customer);
                session.setEntryTime(LocalDateTime.now());
                session.setEntryLane(lane);
                session.setEntryBarrier(face.barrierId());
                session.setParkingLot(lane.getParkingLot());

                if (ticket.isPresent()) {
                    session.setTicketId(ticket.get().getId());
                    session.setSessionType("MONTHLY");
                } else {
                    session.setSessionType("DAILY");
                }

                parkingSessionRepository.save(session);
                log.info("emitMatch(entry): session created id={} plate={} customerId={} sessionType={} ticketId={}",
                        session.getId(), vehicle.getPlateNumber(), customer.getId(), session.getSessionType(),
                        session.getTicketId());
                return;
            }

            if ("exit".equals(direction)) {
                ParkingSessionEntity openSession = parkingSessionRepository
                        .findFirstByVehicleIdAndEntryCustomerIdAndExitTimeIsNullOrderByEntryTimeDesc(
                                vehicle.getId(), customer.getId())
                        .orElseThrow(() -> new ServiceException(
                                "Không tìm thấy parking_session đang mở cho xe và khách hàng tương ứng", 404));

                openSession.setExitCustomer(customer);
                openSession.setExitTime(LocalDateTime.now());
                openSession.setExitLane(lane);
                openSession.setExitBarrier(face.barrierId());
                parkingSessionRepository.save(openSession);

                log.info("emitMatch(exit): session closed id={} plate={} customerId={} sessionType={} ticketId={}",
                        openSession.getId(), vehicle.getPlateNumber(), customer.getId(), openSession.getSessionType(),
                        openSession.getTicketId());
                return;
            }

            throw new ServiceException("Barrier direction không hợp lệ: " + barrier.getDirection(), 400);
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
        private final ReentrantLock lock = new ReentrantLock(); // Each barrier has its own lock and buffer to minimize
                                                                // contention. We only need to match events within the
                                                                // same barrier, so this is sufficient.
        private final Deque<PendingDetection> faces = new ArrayDeque<>(); // pending face detections that have not been
                                                                          // matched yet
        private final Deque<PendingDetection> plates = new ArrayDeque<>(); // pending plate detections that have not
                                                                           // been matched yet

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