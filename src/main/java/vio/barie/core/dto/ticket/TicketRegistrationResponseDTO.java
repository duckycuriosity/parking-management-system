package vio.barie.core.dto.ticket;

import lombok.Builder;
import lombok.Data;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;

import java.time.LocalDateTime;

@Data
@Builder
public class TicketRegistrationResponseDTO {

    private Long ticketId;
    private TicketType ticketType; // "MONTH" or "QUARTER"
    private TicketStatus ticketStatus; // "ACTIVE", "DEAD"

    private Long customerId;
    private String customerName;
    private String customerPhone;

    private Long vehicleId;
    private String plateNumber;
    private String vehicleType;

    private Integer profileId; // AI profile ID

    private LocalDateTime registeredAt;
    private LocalDateTime expireAt;

    private String message;
    private String notes;
}
