package vio.barie.core.dto.ticket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.entity.VehicleEntity;

import java.time.LocalDateTime;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class TicketDTO {
    private Long id;
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private VehicleEntity vehicle;
    private LocalDateTime registerAt;
    private LocalDateTime expireAt;
    private String status; // 'ACTIVE', 'DEAD'
    private LocalDateTime createdAt;
    private String ticketType;
    private Long customerId;
}