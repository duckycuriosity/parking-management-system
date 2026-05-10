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
import vio.barie.core.service.mqtt.ParkingMessageService;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingService {

    private final VehicleRepository vehicleRepository;
    private final TicketRepository ticketRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final CalculatorService calculatorService;
    private final PaymentService paymentService;
    private final ParkingMessageService ioTService;
    private final LaneRepository laneRepository;

    // todo: tạm thời fix cứng
    private final String device = "pk_df102790fdca47ad";

    public void handle(ParkingEvent req){
        VehicleEntity vehicle = findVehicle(req);

        Optional<ParkingSessionEntity> session = parkingSessionRepository
                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId());
        if (session.isPresent()){
            handleExitEvent(req);
        } else handleEntryEvent(req);
    }

    @Transactional
    public void handleEntryEvent(ParkingEvent req) {
        VehicleEntity vehicle = findVehicle(req);
        ParkingCustomerEntity customer = findCustomer(req);
        LaneEntity lane = findLane(req);

        ParkingSessionEntity session = new ParkingSessionEntity();
        session.setVehicle(vehicle);
        session.setEntryCustomer(customer);
        session.setEntryTime(LocalDateTime.now());
        session.setEntryLane(lane);
        session.setParkingLot(lane.getParkingLot());
        parkingSessionRepository.save(session);

        log.info("handleEntryEvent: entry session created — vehicleId={}, plate={}, laneId={}",
                vehicle.getId(), vehicle.getPlateNumber(), req.getLaneId());

        // TODO: từ lane tìm thấy barrier
        ioTService.publishOpenCommand(device, session.getId(), "vehicle_entry");
    }

    // TODO: tạm thời cmt để test
    // @Transactional
    public void handleExitEvent(ParkingEvent req) {
        VehicleEntity vehicle = findVehicle(req);
        ParkingCustomerEntity customer = findCustomer(req);
        LaneEntity lane = findLane(req);

        ParkingSessionEntity session = parkingSessionRepository
                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy hoạt động đi vào của xe", 404));
        session.setExitCustomer(customer);
        session.setExitTime(LocalDateTime.now());
        session.setExitLane(lane);

        // Luồng vé tháng
        TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
        if (ticket != null) {
            parkingSessionRepository.save(session);
            ioTService.publishOpenForMonthlyTicket(device, session.getId(), vehicle.getPlateNumber(),
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

        parkingSessionRepository.save(session);

        // TODO: từ lane tìm thấy barrier
        ioTService.publishVehicleInfo(device, session.getId(), vehicle.getPlateNumber(),
                session.getEntryTime(), amount, paymentService.decodePaymentLink(qr.getPaymentLink()));

        try {
            Thread.sleep(3_000L);
            ioTService.publishPaymentConfirmed(device, session.getId(), amount, qr.getInvoiceNo());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // TODO: thay bằng webhook thanh toán thực, tạm thời tự động mở cổng sau 10s
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ioTService.publishPaymentSuccess(device, session.getId(), vehicle.getPlateNumber());
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

    private ParkingCustomerEntity findCustomer(ParkingEvent req) {
        return parkingCustomerRepository.findByAiIdentityId(req.getAiIdentityCustomerId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy người ", 404));
    }

    private LaneEntity findLane(ParkingEvent req) {
        return laneRepository.findByIdWithParkingLot(req.getLaneId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy lane: ", 404));
    }

    private TicketEntity findValidMonthlyTicket(Long vehicleId) {
        return ticketRepository.findByVehicleIdAndStatus(vehicleId, "ACTIVE")
                .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()))
                .orElse(null);
    }
}
