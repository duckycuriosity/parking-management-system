package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.dto.ticket.TicketPaginationRequest;
import vio.barie.core.dto.ticket.TicketRegisterRequest;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.*;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final VehicleRepository vehicleRepo;
    private final TicketRepository ticketRepo;
    private final PaymentRepository paymentRepo;
    private final TicketPaymentRepository ticketPaymentRepo;

    @Transactional
    public TicketEntity registerTicket(TicketRegisterRequest req)  {

        VehicleEntity vehicle = vehicleRepo.findById(req.getVehicleId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy phương tiện tương ứng !",404));

        PaymentEntity payment = paymentRepo.findById(req.getPaymentId())
                .orElseThrow(() -> new ServiceException("Không tìm hóa đơn tương ứng !",404));

        // tìm xem payment đã gắn với bất kì ticket nào chưa , nếu chưa thì hợp lệ
        if (ticketPaymentRepo.existsByPaymentId(payment.getId())){
            throw new ServiceException("Hóa đơn này không hợp lệ!",404);
        }

        // lấy ra ticket của phương tiện này hoặc tạo ticket mới
        TicketEntity ticket = ticketRepo.findByVehicleIdAndStatus(vehicle.getId(),"ACTIVE").orElseGet(() ->{
            TicketEntity newTicket = new TicketEntity();
            newTicket.setVehicle(vehicle);
            newTicket.setRegisterAt(req.getRegisterAt());
            return newTicket;
        });

        // 1 là ticket hết hạn , 2 là ticket mới hẳn
        if (ticket.getExpireAt() == null || ticket.getExpireAt().isBefore(req.getRegisterAt())) {
            ticket.setExpireAt(req.getRegisterAt());
        }

        if (req.getType().equals("DAY")){
            ticket.setExpireAt(ticket.getExpireAt().plusDays(req.getDuration()));
        } else if (req.getType().equals("MONTH")) {
            ticket.setExpireAt(ticket.getExpireAt().plusMonths(req.getDuration()));
        } else if (req.getType().equals("YEAR")) {
            ticket.setExpireAt(ticket.getExpireAt().plusYears(req.getDuration()));
        } else {
            throw new ServiceException("Đơn vị thời gian không hợp lệ! Chỉ có thể là 'DAY', 'MONTH', 'YEAR'",404);
        }

        ticket.setStatus("ACTIVE");

        ticketRepo.save(ticket);
        return ticket;
    }

    public TicketEntity getById(Long id) {
        return ticketRepo.findById(id)
                .orElseThrow(() -> new ServiceException("Không tìm thấy dữ liệu của ticket này",404));
    }

    public Page<TicketDTO> list(TicketPaginationRequest req) {
        // Tạo pageable
        Pageable pageable = PageRequest.of(
                req.getPage(),
                req.getSize(),
                Sort.by(Sort.Direction.fromString(req.getSortDirection()), req.getSortBy())
        );
        // Tạo specification từ request
        Specification<TicketEntity> spec = TicketSpecifications.buildPaginationSpecification(req.getPlateNumber(), req.getStatus());
        return ticketRepo.findAll(spec, pageable).map(TicketEntity::convertToDTO);
    }
}
