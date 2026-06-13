package vio.barie.core.service.mqtt;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.CalculatorService;
import vio.barie.core.service.PaymentService;
import vio.barie.core.utils.DirectionType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;

@Service
@RequiredArgsConstructor
public class DetectionMatchingService {

    private static final long MATCH_WINDOW_MS = 120_000L; // 2 minutes

    private static final Logger logger = LoggerFactory.getLogger(DetectionMatchingService.class);

    private final BarrierCameraRepository barrierCameraRepository;
    private final BarrierRepository barrierRepository;
    private final TicketRepository ticketRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingMessageService ioTService;
    private final CalculatorService calculatorService;
    private final PaymentService paymentService;
    private final RedisTemplate<String, PendingEventDetection> redisTemplate;

    /**
     * Ingest a detection event and try to match it with its counterpart.
     * Face and plate are produced independently, so we buffer unmatched
     * events and emit only when both sides of the same barrier/direction pair
     * arrive.
     */
    public void ingest(PendingEventDetection detection) {
        if (detection == null) {
            logger.warn("ingest: received null detection");
            ioTService.publishErrorWhenHandlingEvent("Received null detection", "",
                    detection != null ? detection.getType() : null);
            return;
        }

        try {
            if (detection.getDirectionType() == null) {
                logger.warn("ingest: detection has no direction — type={}", detection.getType());
                ioTService.publishErrorWhenHandlingEvent("Detection has no direction", "", detection.getType());
                return;
            }

            String matchKey = buildMatchKey(detection);
            if (matchKey == null) {
                logger.warn(
                        "ingest: cannot resolve barrier for detection type={} direction={} cameraId={} plate={} profileId={}",
                        detection.getType(), detection.getDirectionType(),
                        detection.getDirectionType() == DirectionType.ENTRY ? detection.getEntryCameraId()
                                : detection.getExitCameraId(),
                        detection.getPlateNumber(), detection.getProfileId());
                ioTService.publishErrorWhenHandlingEvent("Cannot resolve barrier for detection", "",
                        detection.getType());
                return;
            }

            purgeExpiredEvents(matchKey, System.currentTimeMillis());
            List<PendingEventDetection> buffer = redisTemplate.opsForList().range(matchKey, 0, -1);
            if (buffer == null) {
                buffer = Collections.emptyList();
            }

            PendingEventDetection matched = findAndRemoveMatch(buffer, detection);
            if (matched == null) {
                redisTemplate.opsForList().rightPush(matchKey, detection);
                redisTemplate.expire(matchKey, Duration.ofSeconds(MATCH_WINDOW_MS / 1000 + 30));
                logger.debug("ingest: buffered unmatched event type={} direction={} key={}",
                        detection.getType(), detection.getDirectionType(), matchKey);
                return;
            }

            redisTemplate.opsForList().remove(matchKey, 1, matched);
            PendingEventDetection merged = mergeEvents(matched, detection);
            logger.info("ingest: matched events type={} direction={} key={}",
                    merged.getType(), merged.getDirectionType(), matchKey);
            emitMatch(merged);
        } catch (Exception ex) {
            logger.error("ingest: failed to process detection — type={} direction={}: {}",
                    detection.getType(), detection.getDirectionType(), ex.getMessage(), ex);
        }
    }

    private void emitMatch(PendingEventDetection detection) {
        if (detection.getDirectionType() == DirectionType.ENTRY) {
            handleEntryEvent(detection);
            return;
        }

        if (detection.getDirectionType() == DirectionType.EXIT) {
            handleExitEvent(detection);
            return;
        }

        logger.warn("emitMatch: unknown direction type={}", detection.getDirectionType());
    }

    @Transactional
    public void handleEntryEvent(PendingEventDetection detection) {
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
                logger.warn("handleEntryEvent: missing vehicle or customer — plate={} profileId={}",
                        detection.getPlateNumber(), detection.getProfileId());
                ioTService.publishErrorWhenHandlingEvent("Vehicle or customer not found for entry event", "",
                        detection.getType());
                return;
            }

            String barrierId = detection.getEntryBarrierId();
            Long laneId = detection.getEntryLaneId();
            if (barrierId == null || laneId == null) {
                logger.warn("handleEntryEvent: missing barrier or lane info");
                ioTService.publishErrorWhenHandlingEvent("Missing barrier or lane info for entry event", "",
                        detection.getType());
                return;
            }

            // Fetch barrier with eager loading
            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                logger.warn("handleEntryEvent: barrier {} has no proper lane/device config", barrierId);
                ioTService.publishErrorWhenHandlingEvent("Invalid barrier configuration for entry event", "",
                        detection.getType());
                return;
            }

            LaneEntity lane = barrier.getParkingDevice().getLane();
            if (lane.getParkingLot() == null) {
                logger.warn("handleEntryEvent: lane {} has no parking lot", laneId);
                ioTService.publishErrorWhenHandlingEvent("Lane has no parking lot for entry event", "",
                        detection.getType());
                return;
            }

            // Check for active monthly ticket
            Optional<TicketEntity> ticket = ticketRepository
                    .findTicketByCustomerIdAndVehicleId(customer.getId(), vehicle.getId())
                    .filter(t -> t.getStatus() == TicketStatus.ACTIVE)
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
                session.setSessionType(SessionType.valueOf(ticket.get().getTicketType().name()));
            } else {
                session.setSessionType(SessionType.DAY);
            }

            parkingSessionRepository.save(session);
            logger.info("handleEntryEvent: session created id={} plate={} customer={} sessionType={} ticketId={}",
                    session.getId(), vehicle.getPlateNumber(), customer.getId(),
                    session.getSessionType(), session.getTicketId());
            ioTService.publishOpenCommand(barrierId, session.getId(), "vehicle_entry");

        } catch (Exception ex) {
            logger.error("handleEntryEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }

    @Transactional
    public void handleExitEvent(PendingEventDetection detection) {
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
                logger.warn("handleExitEvent: missing vehicle or customer — plate={} profileId={}",
                        detection.getPlateNumber(), detection.getProfileId());
                ioTService.publishErrorWhenHandlingEvent("Vehicle or customer not found for exit event", "",
                        detection.getType());
                return;
            }

            String barrierId = detection.getExitBarrierId();
            Long laneId = detection.getExitLaneId();
            if (barrierId == null || laneId == null) {
                logger.warn("handleExitEvent: missing barrier or lane info");
                ioTService.publishErrorWhenHandlingEvent("Missing barrier or lane info for exit event", "",
                        detection.getType());
                return;
            }

            // Fetch open session for this vehicle+customer
            ParkingSessionEntity openSession = parkingSessionRepository
                    .findFirstByVehicleIdAndEntryCustomerIdAndExitTimeIsNullOrderByEntryTimeDesc(
                            vehicle.getId(), customer.getId())
                    .orElse(null);

            if (openSession == null) {
                logger.warn("handleExitEvent: no open parking session found for vehicle={} customer={}",
                        vehicle.getId(), customer.getId());
                ioTService.publishErrorWhenHandlingEvent("No open parking session found for exit event", "",
                        detection.getType());
                return;
            }

            // Fetch barrier with eager loading
            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                ioTService.publishErrorWhenHandlingEvent("Invalid barrier configuration for exit event", "",
                        detection.getType());
                return;
            }

            LaneEntity lane = barrier.getParkingDevice().getLane();

            LocalDateTime exitTime = LocalDateTime.now();

            // Close session
            openSession.setExitCustomer(customer);
            openSession.setExitTime(exitTime);
            openSession.setExitLane(lane);
            openSession.setExitBarrier(barrierId);
            parkingSessionRepository.save(openSession);

            logger.info("handleExitEvent: session closed id={} plate={} customer={} sessionType={} ticketId={}",
                    openSession.getId(), vehicle.getPlateNumber(), customer.getId(),
                    openSession.getSessionType(), openSession.getTicketId());

            TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
            BigDecimal amount;
            if (ticket != null && (ticket.getExpireAt().isAfter(exitTime) || ticket.getExpireAt().isEqual(exitTime))) {
                ioTService.publishOpenForMonthlyTicket(barrierId, openSession.getId(), vehicle.getPlateNumber(),
                        "parking_customer_monthly", ticket.getExpireAt());
                return;
            } else {
                if (ticket != null) {
                    ticket.setStatus(TicketStatus.INACTIVE);
                    ticketRepository.save(ticket);
                }
                TicketEntity expireTicket = findExpireTicket(vehicle.getId());
                if (expireTicket != null && expireTicket.getExpireAt() != null && expireTicket.getExpireAt().isAfter(openSession.getEntryTime())) {
                    amount = calculatorService.calculate(expireTicket.getExpireAt(), LocalDateTime.now());
                } else {
                    amount = calculatorService.calculate(openSession.getEntryTime(), LocalDateTime.now());
                }
            }

            // tao QR
            CreatePaymentRequest payReq = new CreatePaymentRequest();
            payReq.setAmount(amount);
            payReq.setGoodsName("Phi gui xe");
            payReq.setDescription("Phi gui xe bien so " + vehicle.getPlateNumber());
            CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);

            // tao payment
            paymentService.createSessionPayment(
                    qr.getInvoiceNo(),
                    qr.getAmount(),
                    qr.getPayType(),
                    "Phi gui xe bien so " + vehicle.getPlateNumber(),
                    openSession.getId(),
                    barrierId,
                    vehicle.getPlateNumber(),
                    customer.getAiProfileId(),
                    lane.getId(),
                    exitTime);

            ioTService.publishVehicleInfo(barrierId, openSession.getId(), vehicle.getPlateNumber(),
                    openSession.getEntryTime(), amount,
                    // paymentService.decodePaymentLink(qr.getPaymentLink())
                    qr.getPaymentLink());

        } catch (Exception ex) {
            logger.error("handleExitEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }

    private String buildMatchKey(PendingEventDetection detection) {
        String direction = detection.getDirectionType() != null ? detection.getDirectionType().name() : "UNKNOWN";
        String barrierId = resolveBarrierId(detection);
        Long laneId = resolveLaneId(detection);
        if (barrierId == null || barrierId.isBlank() || laneId == null) {
            return null;
        }

        return "detection:pending:" + direction + ":" + barrierId + ":" + laneId;
    }

    private Long resolveLaneId(PendingEventDetection detection) {
        if (detection == null || detection.getDirectionType() == null) {
            return null;
        }

        if (detection.getDirectionType() == DirectionType.ENTRY) {
            return detection.getEntryLaneId();
        }

        if (detection.getDirectionType() == DirectionType.EXIT) {
            return detection.getExitLaneId();
        }

        return null;
    }

    private String resolveBarrierId(PendingEventDetection detection) {
        if (detection == null || detection.getDirectionType() == null) {
            return null;
        }

        if (detection.getDirectionType() == DirectionType.ENTRY) {
            return detection.getEntryBarrierId();
        }

        if (detection.getDirectionType() == DirectionType.EXIT) {
            return detection.getExitBarrierId();
        }

        return null;
    }

    private PendingEventDetection mergeEvents(PendingEventDetection first, PendingEventDetection second) {
        PendingEventDetection merged = new PendingEventDetection();
        merged.setType(first.getType() != null ? first.getType() : second.getType());
        merged.setDirectionType(
                first.getDirectionType() != null ? first.getDirectionType() : second.getDirectionType());
        merged.setPlateNumber(first.getPlateNumber() != null ? first.getPlateNumber() : second.getPlateNumber());
        merged.setProfileId(first.getProfileId() != null ? first.getProfileId() : second.getProfileId());
        merged.setAiPlateId(first.getAiPlateId() != null ? first.getAiPlateId() : second.getAiPlateId());
        merged.setEntryBarrierId(
                first.getEntryBarrierId() != null ? first.getEntryBarrierId() : second.getEntryBarrierId());
        merged.setEntryCameraId(
                first.getEntryCameraId() != null ? first.getEntryCameraId() : second.getEntryCameraId());
        merged.setEntryLaneId(first.getEntryLaneId() != null ? first.getEntryLaneId() : second.getEntryLaneId());
        merged.setExitBarrierId(
                first.getExitBarrierId() != null ? first.getExitBarrierId() : second.getExitBarrierId());
        merged.setExitCameraId(first.getExitCameraId() != null ? first.getExitCameraId() : second.getExitCameraId());
        merged.setExitLaneId(first.getExitLaneId() != null ? first.getExitLaneId() : second.getExitLaneId());
        merged.setTimestampMs(Math.max(first.getTimestampMs(), second.getTimestampMs()));
        return merged;
    }

    private void purgeExpiredEvents(String matchKey, long now) {
        List<PendingEventDetection> events = redisTemplate.opsForList().range(matchKey, 0, -1);
        if (events == null || events.isEmpty()) {
            return;
        }

        for (PendingEventDetection event : events) {
            if (event == null) {
                continue;
            }
            if (now - event.getTimestampMs() > MATCH_WINDOW_MS) {
                redisTemplate.opsForList().remove(matchKey, 1, event);
            }
        }
    }

    private PendingEventDetection findAndRemoveMatch(List<PendingEventDetection> events,
            PendingEventDetection incoming) {
        PendingEventDetection match = null;
        long bestDelta = Long.MAX_VALUE;

        for (PendingEventDetection candidate : events) {
            if (!isComplementary(candidate, incoming)) {
                continue;
            }

            long delta = Math.abs(candidate.getTimestampMs() - incoming.getTimestampMs());
            if (delta <= MATCH_WINDOW_MS && delta < bestDelta) {
                bestDelta = delta;
                match = candidate;
            }
        }

        if (match != null) {
            events.remove(match);
        }

        return match;
    }

    private boolean isComplementary(PendingEventDetection left, PendingEventDetection right) {
        if (left == null || right == null) {
            return false;
        }

        if (left.getDirectionType() == null || right.getDirectionType() == null) {
            return false;
        }

        if (left.getDirectionType() != right.getDirectionType()) {
            return false;
        }

        if (left.getType() == null || right.getType() == null) {
            return false;
        }

        return (left.getType() == vio.barie.core.utils.DetectionType.FACE
                && right.getType() == vio.barie.core.utils.DetectionType.PLATE)
                || (left.getType() == vio.barie.core.utils.DetectionType.PLATE
                        && right.getType() == vio.barie.core.utils.DetectionType.FACE);
    }

    private TicketEntity findValidMonthlyTicket(Long vehicleId) {
        // chỉ lấy ra vé gửi xe
        return ticketRepository.findByVehicleIdAndStatus(vehicleId, TicketStatus.ACTIVE)
                // .filter(t -> t.getExpireAt() != null &&
                // t.getExpireAt().isAfter(LocalDateTime.now()))
                .orElse(null);
    }

    private TicketEntity findExpireTicket(Long vehicleId) {
        // chỉ lấy ra vé gửi xe
        return ticketRepository.findFirstByVehicleIdOrderByExpireAtDesc(vehicleId)
                .orElse(null);
    }
}