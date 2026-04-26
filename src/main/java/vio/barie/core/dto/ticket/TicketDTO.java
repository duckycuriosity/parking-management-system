package vio.barie.core.dto.ticket;

import jakarta.persistence.*;
import lombok.Data;
import vio.barie.core.entity.VehicleEntity;

import java.time.LocalDateTime;

@Data
public class TicketDTO {
    private Long id;
    private VehicleEntity vehicle;
    private LocalDateTime registerAt;
    private LocalDateTime expireAt;
    private String status; // 'ACTIVE', 'DEAD'
    private LocalDateTime createdAt;
}