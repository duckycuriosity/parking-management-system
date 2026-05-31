package vio.barie.core.dto.ticket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;
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
    private TicketStatus status; // 'ACTIVE', 'DEAD'
//    private LocalDateTime createdAt;
    private LocalDateTime startDate;
    private TicketType ticketType;
    private Long customerId;
    private String plateNumber;
    private String vehicleType;
    private String username;
    private String phoneNumber;
}