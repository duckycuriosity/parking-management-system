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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collector;
import java.util.stream.Collectors;

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
    private final BarrierRepository barrierRepository;
    private final ParkingDeviceRepository parkingDeviceRepository;

    public void handle(ParkingEvent req) {
        VehicleEntity vehicle = findVehicle(req);

        Optional<ParkingSessionEntity> session = parkingSessionRepository
                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId());
        if (session.isPresent()) {
            handleExitEvent(req);
        } else
            handleEntryEvent(req);
    }

    @Transactional
    public void handleEntryEvent(ParkingEvent req) {
        VehicleEntity vehicle = findVehicle(req);
        ParkingCustomerEntity customer = findCustomer(req);
        LaneEntity lane = findLane(req, "ENTRY");

        ParkingSessionEntity session = new ParkingSessionEntity();
        session.setVehicle(vehicle);
        session.setEntryCustomer(customer);
        session.setEntryTime(LocalDateTime.now());
        session.setEntryLane(lane);
        session.setParkingLot(lane.getParkingLot());
        parkingSessionRepository.save(session);
        String barrierId = resolveBarrierId(req, lane, "ENTRY");

        log.info("handleEntryEvent: entry session created — vehicleId={}, plate={}, laneId={}",
                vehicle.getId(), vehicle.getPlateNumber(), req.getEntryLane());

        ioTService.publishOpenCommand(barrierId, session.getId(), "vehicle_entry");
    }

    // TODO: tạm thời cmt để test
    // @Transactional
    public void handleExitEvent(ParkingEvent req) {
        VehicleEntity vehicle = findVehicle(req);
        ParkingCustomerEntity customer = findCustomer(req);
        LaneEntity lane = findLane(req, "EXIT");

        ParkingSessionEntity session = parkingSessionRepository
                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy hoạt động đi vào của xe", 404));
        session.setExitCustomer(customer);
        session.setExitTime(LocalDateTime.now());
        session.setExitLane(lane);
        String barrierId = resolveBarrierId(req, lane, "EXIT");

        // Luồng vé tháng
        TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
        if (ticket != null) {
            parkingSessionRepository.save(session);
            ioTService.publishOpenForMonthlyTicket(barrierId, session.getId(), vehicle.getPlateNumber(),
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

        // PaymentEntity payment = new PaymentEntity();
        // payment.setInvoiceNo(qr.getInvoiceNo());
        // payment.setAmount(amount);
        // payment.setCurrency("VND");
        // payment.setPaymentMethod("QR");
        // payment.setStatus("PENDING");
        // paymentRepository.save(payment);

        parkingSessionRepository.save(session);

        ioTService.publishVehicleInfo(barrierId, session.getId(), vehicle.getPlateNumber(),
                session.getEntryTime(), amount, paymentService.decodePaymentLink(qr.getPaymentLink()));

        try {
            Thread.sleep(3_000L);
            ioTService.publishPaymentConfirmed(barrierId, session.getId(), amount, qr.getInvoiceNo());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // TODO: thay bằng webhook thanh toán thực, tạm thời tự động mở cổng sau 10s
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ioTService.publishPaymentSuccess(barrierId, session.getId(), vehicle.getPlateNumber());
    }

    private VehicleEntity findVehicle(ParkingEvent req) {
        if (req.getAiIdentityPlateId() != null) {
            return vehicleRepository.findByPlateNumber(req.getPlateNumber())
                    .orElseThrow(() -> new ServiceException("Không tìm thấy xe", 404));
        }
        return vehicleRepository.findByPlateNumber(req.getPlateNumber())
                .orElseThrow(() -> new ServiceException("Không tìm thấy xe", 404));
    }

    private ParkingCustomerEntity findCustomer(ParkingEvent req) {
        return parkingCustomerRepository.findByAiProfileId(req.getAiIdentityCustomerId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy người ", 404));
    }

    private LaneEntity findLane(ParkingEvent req, String type) {
        if (type.equals("ENTRY")) {
            return laneRepository.findByIdWithParkingLot(req.getEntryLane())
                    .orElseThrow(() -> new ServiceException("Không tìm thấy lane: ", 404));
        } else if (type.equals("EXIT")) {
            return laneRepository.findByIdWithParkingLot(req.getExitLane())
                    .orElseThrow(() -> new ServiceException("Không tìm thấy lane: ", 404));
        } else {
            return null;
        }

    }

    private TicketEntity findValidMonthlyTicket(Long vehicleId) {
        return ticketRepository.findByVehicleIdAndStatus(vehicleId, "ACTIVE")
                .filter(t -> t.getExpireAt() != null && t.getExpireAt().isAfter(LocalDateTime.now()))
                .orElse(null);
    }

    private String resolveBarrierId(ParkingEvent req, LaneEntity lane, String type) {

            List<ParkingDeviceEntity> parkingDevices = parkingDeviceRepository.findByLane_Id(lane.getId());
            List<BarrierEntity> barriers = barrierRepository.findAllByParkingDevice_DeviceId(parkingDevices.getFirst().getDeviceId());
            List<String> ids = barriers.stream()
                    .map(BarrierEntity::getBarrierId)
                    .collect(Collectors.toList());
            boolean isExists;
            if (type.equals("ENTRY")) {
                isExists = ids.contains(req.getEntryBarrier());
                return isExists ?  req.getEntryBarrier() : null;
            } else {
                isExists = ids.contains(req.getExitBarrier());
                return isExists ? req.getExitBarrier() : null;
            }
    }
}
