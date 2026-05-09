package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.parking.ParkingEvent;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingService {

    private final VehicleRepository vehicleRepository;
    private final TicketRepository ticketRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final PaymentRepository paymentRepository;
    private final CalculatorService calculatorService;
    private final PaymentService paymentService;
    private final ParkingMessageService ioTService;
    private final LaneRepository laneRepository;

    private final String barrier= "pk_cb42f9e063514417_01";

    public void handle(ParkingEvent req){
        VehicleEntity vehicle = findVehicle(req);

        Optional<ParkingSessionEntity> session = parkingSessionRepository
                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId());
        if (session.isPresent()){
            handleExitEvent(session.get());
        } else handleEntryEvent(vehicle, req.getLaneId());
    }

    @Transactional
    public void handleEntryEvent(VehicleEntity vehicle, Long laneId) {
        LaneEntity lane = laneRepository.findByIdWithParkingLot(laneId)
                .orElseThrow(() -> new ServiceException("Lane not found: " + laneId, 404));

        ParkingSessionEntity session = new ParkingSessionEntity();
        session.setVehicle(vehicle);
        session.setEntryTime(LocalDateTime.now());
        session.setEntryLane(lane);
        session.setParkingLot(lane.getParkingLot());
        parkingSessionRepository.save(session);

        log.info("handleEntryEvent: entry session created — vehicleId={}, plate={}, laneId={}",
                vehicle.getId(), vehicle.getPlateNumber(), laneId);

        // TODO: từ lane tìm thấy barrier
        ioTService.publishOpenCommand(barrier, session.getId(), vehicle.getPlateNumber(), "manual_override");
    }

    // TODO: tạm thời cmt để test
    // @Transactional
    public void handleExitEvent(ParkingSessionEntity session) {
        VehicleEntity vehicle = session.getVehicle();

        // Luồng vé tháng
        TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
        if (ticket != null) {
            session.setExitTime(LocalDateTime.now());
            parkingSessionRepository.save(session);
            ioTService.publishOpenForMonthlyTicket(barrier, session.getId(), vehicle.getPlateNumber(),
                    "parking_customer_monthly", ticket.getExpireAt());
            return;
        }

        // Luồng vé lượt: tính tiền + tạo payment PENDING + tạo QR + đẩy vehicle_info
        int amount = calculatorService.calculate(session.getEntryTime(), LocalDateTime.now());

        CreatePaymentRequest payReq = new CreatePaymentRequest();
        payReq.setSession(session);
        payReq.setAmount(amount);
        payReq.setGoodsName("Phi gui xe");
        payReq.setDescription("Phi gui xe bien so " + vehicle.getPlateNumber());
        CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);

//        PaymentEntity payment = new PaymentEntity();
//        payment.setInvoiceNo(qr.getInvoiceNo());
//        payment.setAmount(amount);
//        payment.setCurrency("VND");
//        payment.setPaymentMethod("QR");
//        payment.setStatus("PENDING");
//        paymentRepository.save(payment);

        session.setExitTime(LocalDateTime.now());
        parkingSessionRepository.save(session);

        // TODO: từ lane tìm thấy barrier
        ioTService.publishVehicleInfo(barrier, session.getId(), vehicle.getPlateNumber(),
                session.getEntryTime(), amount, paymentService.decodePaymentLink(qr.getPaymentLink()));

        // TODO: thay bằng webhook thanh toán thực, tạm thời tự động mở cổng sau 10s
        try {
            Thread.sleep(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ioTService.publishPaymentSuccess(barrier, session.getId(), vehicle.getPlateNumber());
    }

    private VehicleEntity findVehicle(ParkingEvent req) {
        if (req.getAiIdentityPlateId() != null) {
            return vehicleRepository.findByAiIdentityPlateId(req.getAiIdentityPlateId())
                    .or(() -> vehicleRepository.findByPlateNumber(req.getPlateNumber()))
                    .orElseThrow(() -> new ServiceException("Không tìm thấy xe", 404));
        }
        return vehicleRepository.findByPlateNumber(req.getPlateNumber())
                .orElseThrow(() -> new ServiceException("Không tìm thấy xe", 404));
    }

    private TicketEntity findValidMonthlyTicket(Long vehicleId) {
        return ticketRepository.findByVehicleIdAndStatus(vehicleId, "ACTIVE")
                .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()))
                .orElse(null);
    }
}
