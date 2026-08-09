package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.TicketCreateRequest;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.dto.ticket.TicketPaginationRequest;
import vio.barie.core.dto.ticket.TicketRegisterRequest;
import vio.barie.core.dto.ticket.TicketRegistrationResponseDTO;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.*;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {
    private final VehicleRepository vehicleRepo;
    private final TicketRepository ticketRepo;
    private final PaymentRepository paymentRepo;
    private final TicketPaymentRepository ticketPaymentRepo;
    private final ParkingCustomerRepository customerRepo;
    private final ProfileService profileService;
    private final ParkingSessionRepository parkingSessionRepo;

    @Transactional
    public TicketEntity registerTicket(TicketRegisterRequest req) {

        VehicleEntity vehicle = vehicleRepo.findById(req.getVehicleId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy phương tiện tương ứng !", 404));

        PaymentEntity payment = paymentRepo.findById(req.getPaymentId())
                .orElseThrow(() -> new ServiceException("Không tìm hóa đơn tương ứng !", 404));

        // tìm xem payment đã gắn với bất kì ticket nào chưa , nếu chưa thì hợp lệ
        if (ticketPaymentRepo.existsByPaymentId(payment.getId())) {
            throw new ServiceException("Hóa đơn này không hợp lệ!", 404);
        }

        // lấy ra ticket của phương tiện này hoặc tạo ticket mới
        TicketEntity ticket = ticketRepo.findByVehicleIdAndStatus(vehicle.getId(), TicketStatus.ACTIVE)
                .orElseGet(() -> {
                    TicketEntity newTicket = new TicketEntity();
                    newTicket.setVehicle(vehicle);
                    newTicket.setRegisterAt(req.getRegisterAt());
                    return newTicket;
                });

        // 1 là ticket hết hạn , 2 là ticket mới hẳn
        if (ticket.getExpireAt() == null || ticket.getExpireAt().isBefore(req.getRegisterAt())) {
            ticket.setExpireAt(req.getRegisterAt());
        }

        if (req.getType().equals("DAY")) {
            ticket.setExpireAt(ticket.getExpireAt().plusDays(req.getDuration()));
        } else if (req.getType().equals("MONTH")) {
            ticket.setExpireAt(ticket.getExpireAt().plusMonths(req.getDuration()));
        } else {
            throw new ServiceException("Đơn vị thời gian không hợp lệ! Chỉ có thể là 'DAY', 'MONTH', 'QUARTER'", 404);
        }

        ticket.setStatus(TicketStatus.ACTIVE);

        ticketRepo.save(ticket);
        return ticket;
    }

    @Transactional
    public TicketDTO createTicket(TicketCreateRequest req) {
        VehicleEntity vehicle = vehicleRepo.findVehicleById(req.getVehicleId())
                .orElseThrow(() -> new ServiceException("Not found vehicle", 404));
        TicketEntity ticket = new TicketEntity();
        ticket.setVehicle(vehicle);
        if (req.getCustomerId() != null) {
            ParkingCustomerEntity customer = customerRepo.findById(req.getCustomerId())
                    .orElseThrow(() -> new ServiceException("Not found customer", 404));
            ticket.setCustomer(customer);
        }
        ticket.setTicketType(req.getTicketType());
        ticket.setRegisterAt(req.getRegisterAt());
        ticket.setExpireAt(req.getExpireAt());

        // mặc định trạng thái DEAD
//        if (req.getStatus() != null) {
//            ticket.setStatus(req.getStatus());
//        } else {
//            ticket.setStatus(TicketStatus.DEAD);
//        }
        ticket.setStatus(TicketStatus.DEAD);
        ticket = ticketRepo.save(ticket);
        return TicketEntity.convertToDTO(ticket);
    }

    @Transactional(readOnly = true)
    public List<TicketDTO> getAllTickets() {
        return ticketRepo.findAll().stream()
                .map(TicketEntity::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TicketDTO getById(Long id) {
        TicketEntity entity = ticketRepo.findById(id)
                .orElseThrow(() -> new ServiceException("Không tìm thấy dữ liệu của ticket này", 404));
        return TicketEntity.convertToDTO(entity);
    }

    @Transactional
    public void deleteTicketById(Long id) {
        TicketEntity ticket = ticketRepo.findById(id)
                .orElseThrow(() -> new ServiceException("Không tìm thấy dữ liệu của ticket để xóa!", 404));
        ParkingCustomerEntity customer = ticket.getCustomer();

        if (customer != null) {
            boolean hasSession = parkingSessionRepo.existsByEntryCustomerId(customer.getId());
            if (hasSession) {
                throw new ServiceException("Người dùng và phương tiện đã phát sinh phiên gửi xe, không thể xóa vé", 400);
            }
        }
        Integer aiProfileId = (customer != null) ? customer.getAiProfileId() : null;
        ticketRepo.delete(ticket);
        ticketRepo.flush();
        log.info("Xóa vé ID: {}. Data (Vehicle) được giữ nguyên trong db", id);
        if (customer != null) {
            try {
                customerRepo.delete(customer);
                customerRepo.flush();
            } catch (DataIntegrityViolationException e) {
                log.warn("Không thể xóa customer do ràng buộc khóa ngoại: {}", e.getMessage());
                throw new ServiceException("Người dùng đã phát sinh data trong hệ thống, không thể xóa!", 400);
            }
            if (aiProfileId != null) {
                try {
                    customerRepo.delete(customer);
                    profileService.delete(aiProfileId);
                    log.info("Đã xóa đồng bộ AI Profile ID: {} thành công.", aiProfileId);
                } catch (Exception e) {
                    log.error("Lỗi hệ thống AI khi xóa profile {}: {}", aiProfileId, e.getMessage());
                    throw new ServiceException("Hệ thống AI đang gặp sự cố, không thể đồng bộ xóa khuôn mặt. Hủy thao tác xóa vé!", 500);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<TicketRegistrationResponseDTO> list(TicketPaginationRequest req) {

        Pageable pageable = PageRequest.of(
                req.getPage(),
                req.getSize(),
                Sort.by(Sort.Direction.fromString(req.getSortDirection()), req.getSortBy())
        );
        String statusFilter = (req.getStatus() != null) ? req.getStatus().name() : null;

        Page<TicketRegistrationResponseDTO> ticketPage = ticketRepo.searchTickets(
                req.getSearchKey(),
                statusFilter,
                pageable
        );


        return ticketPage;
    }

    private TicketStatus calculateDynamicStatus(LocalDateTime registerAt, LocalDateTime expireAt) {
        LocalDateTime now = LocalDateTime.now();
        if (expireAt != null && now.isAfter(expireAt)) {
            return TicketStatus.INACTIVE;
        } else if (registerAt != null && expireAt != null && !now.isBefore(registerAt) && !now.isAfter(expireAt)) {
            return TicketStatus.ACTIVE;
        }
        return TicketStatus.DEAD;
    }


}
