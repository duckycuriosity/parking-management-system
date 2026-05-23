package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import com.nimbusds.jose.shaded.gson.JsonObject;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    // todo: migrate phan nay qua detection matching
//    public void handle(ParkingEvent req) {
//        VehicleEntity vehicle = findVehicle(req.getAiIdentityPlateId(),req.getPlateNumber());
//
//        Optional<ParkingSessionEntity> session = parkingSessionRepository
//                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId());
//        if (session.isPresent()) {
//            handleExitEvent(req);
//        } else
//            handleEntryEvent(req);
//    }

//    @Transactional
//    public void handleEntryEvent(ParkingEvent req) {
//        VehicleEntity vehicle = findVehicle(req);
//        ParkingCustomerEntity customer = findCustomer(req);
//        LaneEntity lane = findLane(req, "ENTRY");
//
//        ParkingSessionEntity session = new ParkingSessionEntity();
//        session.setVehicle(vehicle);
//        session.setEntryCustomer(customer);
//        session.setEntryTime(LocalDateTime.now());
//        session.setEntryLane(lane);
//        session.setParkingLot(lane.getParkingLot());
//        parkingSessionRepository.save(session);
//        String barrierId = resolveBarrierId(req, lane, "ENTRY");
//
//        log.info("handleEntryEvent: entry session created — vehicleId={}, plate={}, laneId={}",
//                vehicle.getId(), vehicle.getPlateNumber(), req.getEntryLane());
//
//        ioTService.publishOpenCommand(barrierId, session.getId(), "vehicle_entry");
//    }

//    @Transactional
//    public void handleExitEvent(ParkingEvent req) {
//        // tim parking session entity trong db
//        LocalDateTime exitTime = LocalDateTime.now();
//        VehicleEntity vehicle = findVehicle(req);
//        ParkingCustomerEntity customer = findCustomer(req);
//        LaneEntity lane = findLane(req, "EXIT");
//        String barrierId = resolveBarrierId(req, lane, "EXIT");
//
//        ParkingSessionEntity session = parkingSessionRepository
//                .findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(vehicle.getId())
//                .orElseThrow(() -> new ServiceException("Không tìm thấy hoạt động đi vào của xe", 404));
//
//        // Luồng vé tháng
//        TicketEntity ticket = findValidMonthlyTicket(vehicle.getId());
//        BigDecimal amount = null;
//        if (ticket != null && (ticket.getExpireAt().isAfter(exitTime) || ticket.getExpireAt().isEqual(exitTime))) {
//            session.setExitCustomer(customer);
//            session.setExitTime(exitTime);
//            session.setExitLane(lane);
//            parkingSessionRepository.save(session);
//            ioTService.publishOpenForMonthlyTicket(barrierId, session.getId(), vehicle.getPlateNumber(),
//                    "parking_customer_monthly", ticket.getExpireAt());
//            return;
//        } else if (ticket != null && ticket.getExpireAt().isBefore(exitTime)) {
//
//        } else {
//            amount = calculatorService.calculate(session.getEntryTime(), LocalDateTime.now());
//        }
//
//        // tao QR
//        CreatePaymentRequest payReq = new CreatePaymentRequest();
//        payReq.setAmount(amount);
//        payReq.setGoodsName("Phi gui xe");
//        payReq.setDescription("Phi gui xe bien so " + vehicle.getPlateNumber());
//        CreatePaymentResponse qr = paymentService.createPaymentLink(payReq);
//
//        // tao payment
//        paymentService.createSessionPayment(
//                qr.getInvoiceNo(),
//                qr.getAmount(),
//                qr.getPayType(),
//                "Phi gui xe bien so " + vehicle.getPlateNumber(),
//                session.getId(),
//                barrierId,
//                vehicle.getPlateNumber(),
//                req.getAiIdentityCustomerId(),
//                lane.getId(),
//                exitTime);
//
//        ioTService.publishVehicleInfo(barrierId, session.getId(), vehicle.getPlateNumber(),
//                session.getEntryTime(), amount,
////                paymentService.decodePaymentLink(qr.getPaymentLink())
//                qr.getQrCode()
//        );
//    }

//    public void handleExitEventPaymentConfirmed(Long sessionId, String barrierId, String invoiceNo, BigDecimal amount){
//        ioTService.publishPaymentConfirmed(barrierId, sessionId, amount, invoiceNo);
//    }
//
//    @Transactional
//    public void handleExitEventCallback(Long sessionId, String barrierId, String plateNumber, Integer aiIdentityCustomerId, Long laneId, LocalDateTime exitTime){
//        ParkingCustomerEntity customer = findCustomer(aiIdentityCustomerId);
//        LaneEntity lane = findLane(laneId);
//        ParkingSessionEntity session = parkingSessionRepository
//                .findById(sessionId)
//                .orElseThrow(() -> new ServiceException("Không tìm thấy session: " + sessionId, 404));
//        session.setExitCustomer(customer);
//        session.setExitTime(exitTime);
//        session.setExitLane(lane);
//        parkingSessionRepository.save(session);
//
//        ioTService.publishPaymentSuccess(barrierId, sessionId, plateNumber);
//    }

//    private VehicleEntity findVehicle(Integer aiIdentityPlateId, String plateNumber) {
//        if (aiIdentityPlateId != null) {
//            return vehicleRepository.findByAiIdentityPlateId(plateNumber)
//                    .orElseThrow(() -> new ServiceException("Không tìm thấy xe", 404));
//        }
//        return vehicleRepository.findByPlateNumber(plateNumber)
//                .orElseThrow(() -> new ServiceException("Không tìm thấy xe", 404));
//    }
//
//    private ParkingCustomerEntity findCustomer(Integer aiIdentityCustomerId) {
//        return parkingCustomerRepository.findByAiProfileId(aiIdentityCustomerId)
//                .orElseThrow(() -> new ServiceException("Không tìm thấy người ", 404));
//    }
//
//    private LaneEntity findLane(ParkingEvent req, String type) {
//        if (type.equals("ENTRY")) {
//            return laneRepository.findByIdWithParkingLot(req.getEntryLane())
//                    .orElseThrow(() -> new ServiceException("Không tìm thấy lane: ", 404));
//        } else if (type.equals("EXIT")) {
//            return laneRepository.findByIdWithParkingLot(req.getExitLane())
//                    .orElseThrow(() -> new ServiceException("Không tìm thấy lane: ", 404));
//        } else {
//            return null;
//        }
//
//    }
//
//    private TicketEntity findValidMonthlyTicket(Long vehicleId) {
//        return ticketRepository.findByVehicleIdAndStatus(vehicleId, "ACTIVE")
//                .orElse(null);
//    }
//
//    private String resolveBarrierId(ParkingEvent req, LaneEntity lane, String type) {
//
//            List<ParkingDeviceEntity> parkingDevices = parkingDeviceRepository.findByLane_Id(lane.getId());
//            List<BarrierEntity> barriers = barrierRepository.findAllByParkingDevice_DeviceId(parkingDevices.getFirst().getDeviceId());
//            List<String> ids = barriers.stream()
//                    .map(BarrierEntity::getBarrierId)
//                    .collect(Collectors.toList());
//            boolean isExists;
//            if (type.equals("ENTRY")) {
//                isExists = ids.contains(req.getEntryBarrier());
//                return isExists ?  req.getEntryBarrier() : null;
//            } else {
//                isExists = ids.contains(req.getExitBarrier());
//                return isExists ? req.getExitBarrier() : null;
//            }
//    }
}
