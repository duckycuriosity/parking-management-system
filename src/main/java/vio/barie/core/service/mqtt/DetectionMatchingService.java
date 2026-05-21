package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionMatchingService {

    private static final Logger logger = LoggerFactory.getLogger(DetectionMatchingService.class);

    private final BarrierCameraRepository barrierCameraRepository;
    private final BarrierRepository barrierRepository;
    private final TicketRepository ticketRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionRepository parkingSessionRepository;

    public Optional<String> resolveBarrierId(Long cameraId, String role, Long laneId) {
        try {
            if (cameraId == null || laneId == null) {
                return Optional.empty();
            }

            // Exact match: camera_id + role (each camera maps to one barrier with one role)
            List<BarrierCameraEntity> mappings = barrierCameraRepository.findByCameraIdAndRoleIgnoreCase(cameraId,
                    role);
            if (mappings.isEmpty()) {
                log.debug("resolveBarrierId: no mapping found for cameraId={} role={}", cameraId, role);
                return Optional.empty();
            }

            // Take first mapping (should be exactly 1)
            String barrierId = mappings.get(0).getBarrierId();
            if (barrierId == null || barrierId.isBlank()) {
                return Optional.empty();
            }

            // Validate lane: fetch barrier with eager loading to avoid
            // LazyInitializationException
            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
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
        } catch (Exception ex) {
            log.error("resolveBarrierId: failed to resolve barrier for cameraId={} role={} laneId={}: {}",
                    cameraId, role, laneId, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    /**
     * Ingest a detection event with direction already determined.
     * No need for buffer matching since direction (entry/exit) is known upfront.
     * Simply emit the event immediately based on direction.
     */
    public void ingest(PendingEventDetection detection) {
        if (detection == null) {
            log.warn("ingest: received null detection");
            return;
        }

        try {
            if (detection.getDirectionType() == null) {
                log.warn("ingest: detection has no direction — type={}", detection.getType());
                return;
            }

            switch (detection.getDirectionType()) {
                case ENTRY:
                    handleEntryEvent(detection);
                    break;
                case EXIT:
                    handleExitEvent(detection);
                    break;
                default:
                    log.warn("ingest: unknown direction type={}", detection.getDirectionType());
            }
        } catch (Exception ex) {
            log.error("ingest: failed to process detection — type={} direction={}: {}",
                    detection.getType(), detection.getDirectionType(), ex.getMessage(), ex);
        }
    }

    private void handleEntryEvent(PendingEventDetection detection) {
        try {
            VehicleEntity vehicle = null;
            if (detection.getPlateNumber() != null) {
                vehicle = vehicleRepository.findPlateByPlateNumber(detection.getPlateNumber());
            }

            ParkingCustomerEntity customer = null;
            if (detection.getProfileId() != null) {
                customer = parkingCustomerRepository.findByAiProfileId(detection.getProfileId()).orElse(null);
            }

            if (vehicle == null || customer == null) {
                log.warn("handleEntryEvent: missing vehicle or customer — plate={} profileId={}",
                        detection.getPlateNumber(), detection.getProfileId());
                return;
            }

            String barrierId = detection.getEntryBarrierId();
            Long laneId = detection.getEntryLaneId();
            if (barrierId == null || laneId == null) {
                log.warn("handleEntryEvent: missing barrier or lane info");
                return;
            }

            // Fetch barrier with eager loading
            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                log.warn("handleEntryEvent: barrier {} has no proper lane/device config", barrierId);
                return;
            }

            LaneEntity lane = barrier.getParkingDevice().getLane();
            if (lane.getParkingLot() == null) {
                log.warn("handleEntryEvent: lane {} has no parking lot", laneId);
                return;
            }

            // Check for active monthly ticket
            Optional<TicketEntity> ticket = ticketRepository
                    .findTicketByCustomerIdAndVehicleId(customer.getId(), vehicle.getId())
                    .filter(t -> "ACTIVE".equalsIgnoreCase(t.getStatus()))
                    .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()));

            // Create parking session
            ParkingSessionEntity session = new ParkingSessionEntity();
            session.setVehicle(vehicle);
            session.setEntryCustomer(customer);
            session.setEntryTime(LocalDateTime.now());
            session.setEntryLane(lane);
            session.setEntryBarrier(barrierId);
            session.setParkingLot(lane.getParkingLot());

            if (ticket.isPresent()) {
                session.setTicketId(ticket.get().getId());
                session.setSessionType("MONTHLY");
            } else {
                session.setSessionType("DAILY");
            }

            parkingSessionRepository.save(session);
            log.info("handleEntryEvent: session created id={} plate={} customer={} sessionType={} ticketId={}",
                    session.getId(), vehicle.getPlateNumber(), customer.getId(),
                    session.getSessionType(), session.getTicketId());

        } catch (Exception ex) {
            log.error("handleEntryEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }

    private void handleExitEvent(PendingEventDetection detection) {
        try {
            VehicleEntity vehicle = null;
            if (detection.getPlateNumber() != null) {
                vehicle = vehicleRepository.findPlateByPlateNumber(detection.getPlateNumber());
            }

            ParkingCustomerEntity customer = null;
            if (detection.getProfileId() != null) {
                customer = parkingCustomerRepository.findByAiProfileId(detection.getProfileId()).orElse(null);
            }

            if (vehicle == null || customer == null) {
                log.warn("handleExitEvent: missing vehicle or customer — plate={} profileId={}",
                        detection.getPlateNumber(), detection.getProfileId());
                return;
            }

            String barrierId = detection.getExitBarrierId();
            Long laneId = detection.getExitLaneId();
            if (barrierId == null || laneId == null) {
                log.warn("handleExitEvent: missing barrier or lane info");
                return;
            }

            // Fetch open session for this vehicle+customer
            ParkingSessionEntity openSession = parkingSessionRepository
                    .findFirstByVehicleIdAndEntryCustomerIdAndExitTimeIsNullOrderByEntryTimeDesc(
                            vehicle.getId(), customer.getId())
                    .orElse(null);

            if (openSession == null) {
                log.warn("handleExitEvent: no open parking session found for vehicle={} customer={}",
                        vehicle.getId(), customer.getId());
                return;
            }

            // Fetch barrier with eager loading
            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                log.warn("handleExitEvent: barrier {} has no proper lane/device config", barrierId);
                return;
            }

            LaneEntity lane = barrier.getParkingDevice().getLane();

            // Close session
            openSession.setExitCustomer(customer);
            openSession.setExitTime(LocalDateTime.now());
            openSession.setExitLane(lane);
            openSession.setExitBarrier(barrierId);
            parkingSessionRepository.save(openSession);

            log.info("handleExitEvent: session closed id={} plate={} customer={} sessionType={} ticketId={}",
                    openSession.getId(), vehicle.getPlateNumber(), customer.getId(),
                    openSession.getSessionType(), openSession.getTicketId());

        } catch (Exception ex) {
            log.error("handleExitEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }
}