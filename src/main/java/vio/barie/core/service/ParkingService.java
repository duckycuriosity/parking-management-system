package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.parking.ParkingEvent;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.repository.VehicleRepository;

import java.time.LocalDateTime;

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


    public void handleEntryEvent(ParkingEvent req) {

    }

    @Transactional
    public void handleExitEvent(ParkingEvent req) {
        VehicleEntity vehicle = findVehicle(req);

        ParkingSessionEntity session = parkingSessionRepository
                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy session đang mở cho xe này", 404));

        // Luồng vé tháng
        TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
        if (ticket != null) {
            session.setExitTime(LocalDateTime.now());
            parkingSessionRepository.save(session);
            // todo: cần truyền tham số
            ioTService.publishOpenForMonthlyTicket();
        }

        // Luồng vé lượt : tính tiền + tạo payment PENDING + tạo QR + đẩy vehicle_info
        int amount = calculatorService.calculate(session.getEntryTime(), LocalDateTime.now());

        CreatePaymentRequest payReq = new CreatePaymentRequest();
        payReq.setSession(session);
        payReq.setAmount(amount);
        payReq.setGoodsName("Phi gui xe");
        payReq.setDescription("Phi gui xe bien so " + vehicle.getPlateNumber());
        CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);

        PaymentEntity payment = new PaymentEntity();
        payment.setInvoiceNo(qr.getInvoiceNo());
        payment.setAmount(amount);
        payment.setCurrency("VND");
        payment.setPaymentMethod("QR");
        payment.setStatus("PENDING");
        paymentRepository.save(payment);

        // todo: cần truyền tham số
        ioTService.publishVehicleInfo();
        // todo: kiem soat va dam bao chi duoc 3p phai xac nhan duoc trang thai thanh toan va mo cong cho khach
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
