package vio.barie.core.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.SessionType;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.dto.LaneEventCommand;
import vio.barie.core.dto.LaneEventImage;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.CalculatorService;
import vio.barie.core.service.PaymentService;
import vio.barie.core.service.ParkingFeeWorkflowGuard;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.smoothing.SmoothingAIEvents;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Session open/close, monthly-ticket lookup, fee calculation and payment-QR creation,
 * shared by every {@link VehicleVerificationService} strategy so this logic exists once
 * regardless of which evidence (face+plate, plate-only, plate+RFID, ...) gated the entry/exit.
 * {@code customer} is null whenever the active strategy doesn't use face identity (e.g. PLATE_ONLY).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingSessionLifecycleService {

    private final TicketRepository ticketRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingMessageService ioTService;
    private final CalculatorService calculatorService;
    private final PaymentService paymentService;
    private final SmoothingAIEvents smoothingAIEvents;
    private ParkingFeeWorkflowGuard feeWorkflowGuard;

    @Autowired
    public void setFeeWorkflowGuard(ParkingFeeWorkflowGuard guard) {
        this.feeWorkflowGuard = guard;
    }

    public Optional<ParkingSessionEntity> findOpenSessionForVehicle(Long vehicleId) {
        return parkingSessionRepository.findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicleId);
    }

    @Transactional
    public Optional<ParkingSessionEntity> openEntry(VehicleEntity vehicle, ParkingCustomerEntity customer,
                                                     BarrierEntity barrier, PendingEventDetection detection) {
        Optional<ParkingSessionEntity> alreadyOpenSession = findOpenSessionForVehicle(vehicle.getId());
        if (alreadyOpenSession.isPresent()) {
            log.info("openEntry: vehicle {} (plate={}) already has an open session id={} — skip duplicate entry",
                    vehicle.getId(), vehicle.getPlateNumber(), alreadyOpenSession.get().getId());
            return Optional.empty();
        }

        LaneEntity lane = barrier.getParkingDevice().getLane();
        String barrierId = barrier.getBarrierId();

        Optional<TicketEntity> ticket = findActiveMonthlyTicket(vehicle, customer);

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

        if (detection.getListImageInfo() != null && !detection.getListImageInfo().isEmpty()) {
            session.setEntryImageInfo(detection.getListImageInfo());
        }

        parkingSessionRepository.save(session);
        log.info("openEntry: session created id={} plate={} customer={} sessionType={} ticketId={}",
                session.getId(), vehicle.getPlateNumber(), customer != null ? customer.getId() : null,
                session.getSessionType(), session.getTicketId());
        ioTService.publishOpenCommand(barrierId, session.getId(), "vehicle_entry");
        ioTService.publishLaneEvent(LaneEventCommand.builder()
                .event(LaneEventCommand.ENTRY_SUCCESS)
                .laneId(lane.getId())
                .barrierId(barrierId)
                .directionType(DirectionType.ENTRY)
                .plate(vehicle.getPlateNumber())
                .vehicleType(vehicle.getVehicleType())
                .profileId(customer != null ? customer.getAiProfileId() : null)
                .sessionId(session.getId())
                .images(LaneEventImage.from("entry_", detection.getListImageInfo()))
                .eventTs(System.currentTimeMillis())
                .build());
        smoothingAIEvents.markLaneCooldown(detection);
        return Optional.of(session);
    }

    @Transactional
    public void closeExit(ParkingSessionEntity openSession, ParkingCustomerEntity customer,
                          BarrierEntity barrier, PendingEventDetection detection) {
        if (feeWorkflowGuard != null)
            openSession = feeWorkflowGuard.lockAndAssertLegacyWorkflowAllowed(openSession.getId());
        LaneEntity lane = barrier.getParkingDevice().getLane();
        String barrierId = barrier.getBarrierId();
        VehicleEntity vehicle = openSession.getVehicle();
        LocalDateTime exitTime = LocalDateTime.now();

        openSession.setExitCustomer(customer);
        openSession.setExitTime(exitTime);
        openSession.setExitLane(lane);
        openSession.setExitBarrier(barrierId);
        openSession.setExitImageInfo(detection.getListImageInfo());

        parkingSessionRepository.save(openSession);
        List<LaneEventImage> exitImages = new ArrayList<>(LaneEventImage.from("entry_", openSession.getEntryImageInfo()));
        exitImages.addAll(LaneEventImage.from("exit_", detection.getListImageInfo()));
        ioTService.publishLaneEvent(LaneEventCommand.builder()
                .event(LaneEventCommand.EXIT_SUCCESS)
                .laneId(lane.getId())
                .barrierId(barrierId)
                .directionType(DirectionType.EXIT)
                .plate(vehicle.getPlateNumber())
                .vehicleType(vehicle.getVehicleType())
                .profileId(customer != null ? customer.getAiProfileId() : null)
                .sessionId(openSession.getId())
                .images(exitImages)
                .eventTs(System.currentTimeMillis())
                .build());
        smoothingAIEvents.markLaneCooldown(detection);

        log.info("closeExit: session closed id={} plate={} customer={} sessionType={} ticketId={}",
                openSession.getId(), vehicle.getPlateNumber(), customer != null ? customer.getId() : null,
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
            if (expireTicket != null && expireTicket.getExpireAt() != null
                    && expireTicket.getExpireAt().isAfter(openSession.getEntryTime())) {
                amount = calculatorService.calculate(expireTicket.getExpireAt(), LocalDateTime.now());
            } else {
                amount = calculatorService.calculate(openSession.getEntryTime(), LocalDateTime.now());
            }
        }

        CreatePaymentRequest payReq = new CreatePaymentRequest();
        payReq.setAmount(amount);
        payReq.setGoodsName("Phi gui xe");
        payReq.setDescription("Phi gui xe bien so " + vehicle.getPlateNumber());
        CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);

        paymentService.createSessionPayment(
                qr.getInvoiceNo(),
                qr.getAmount(),
                qr.getPayType(),
                "Phi gui xe bien so " + vehicle.getPlateNumber(),
                openSession.getId(),
                barrierId,
                vehicle.getPlateNumber(),
                customer != null ? customer.getAiProfileId() : null,
                lane.getId(),
                exitTime);

        ioTService.publishVehicleInfo(barrierId, openSession.getId(), vehicle.getPlateNumber(),
                openSession.getEntryTime(), amount, qr.getPaymentLink());
    }

    private Optional<TicketEntity> findActiveMonthlyTicket(VehicleEntity vehicle, ParkingCustomerEntity customer) {
        if (customer != null) {
            return ticketRepository.findTicketByCustomerIdAndVehicleId(customer.getId(), vehicle.getId())
                    .filter(t -> t.getStatus() == TicketStatus.ACTIVE)
                    .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()));
        }
        return ticketRepository.findByVehicleIdAndStatus(vehicle.getId(), TicketStatus.ACTIVE)
                .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()));
    }

    private TicketEntity findValidMonthlyTicket(Long vehicleId) {
        return ticketRepository.findByVehicleIdAndStatus(vehicleId, TicketStatus.ACTIVE).orElse(null);
    }

    private TicketEntity findExpireTicket(Long vehicleId) {
        return ticketRepository.findFirstByVehicleIdOrderByExpireAtDesc(vehicleId).orElse(null);
    }
}
