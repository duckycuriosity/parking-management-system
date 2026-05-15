package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.dto.ticket.TicketDTO;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "ticket")
@Data
@EqualsAndHashCode(callSuper = true)
public class TicketEntity extends BaseEntity {

    @Column(name = "ticket_type")
    private String ticketType;

    @Column(name = "customer_id")
    private Long customerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private VehicleEntity vehicle;

    @Column(name = "register_at", nullable = false)
    private LocalDateTime registerAt;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    @Column(name = "status")
    private String status; // 'ACTIVE', 'DEAD'

    public static TicketDTO convertToDTO(TicketEntity entity) {
        TicketDTO dto = new TicketDTO();
        dto.setId(entity.getId());
        dto.setVehicle(entity.getVehicle());
        dto.setRegisterAt(entity.getRegisterAt());
        dto.setExpireAt(entity.getExpireAt());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setTicketType(entity.getTicketType());
        dto.setCustomerId(entity.getCustomerId());
        return dto;
    }
}