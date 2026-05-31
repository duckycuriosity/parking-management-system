package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "ticket")
@Data
@EqualsAndHashCode(callSuper = true)
public class TicketEntity extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type")
    private TicketType ticketType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private ParkingCustomerEntity customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private VehicleEntity vehicle;

    @Column(name = "register_at", nullable = false)
    private LocalDateTime registerAt;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TicketStatus status; // 'ACTIVE','DEAD'

    public static TicketDTO convertToDTO(TicketEntity entity) {
        TicketDTO dto = new TicketDTO();
        dto.setId(entity.getId());
        dto.setVehicle(entity.getVehicle());
        dto.setRegisterAt(entity.getRegisterAt());
        dto.setExpireAt(entity.getExpireAt());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setTicketType(entity.getTicketType());
        if (entity.getCustomer() != null) {
            dto.setCustomerId(entity.getCustomer().getId());
            dto.setUsername(entity.getCustomer().getUsername());
            dto.setPhoneNumber(entity.getCustomer().getPhoneNumber());
        }
        if (entity.getVehicle() != null) {
            dto.setPlateNumber(entity.getVehicle().getPlateNumber());
            dto.setVehicleType(entity.getVehicle().getVehicleType());
        }
        return dto;
    }
}