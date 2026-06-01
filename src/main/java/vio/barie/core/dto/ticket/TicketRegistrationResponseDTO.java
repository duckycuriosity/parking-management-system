package vio.barie.core.dto.ticket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketRegistrationResponseDTO {

    private Long ticketId;
    private String ticketType; // "MONTH" or "QUARTER"
    private String ticketStatus; // "ACTIVE", "DEAD"

    private Long customerId;
    private String customerName;
    // private String customerPhone;

    private Long vehicleId;
    private String plateNumber;
    private String vehicleType;

    private Integer profileId; // AI profile ID
    private String customerImageUrl;

    private LocalDateTime registeredAt;
    private LocalDateTime expireAt;
    private LocalDateTime startDate;

    private String message;
    private String notes;
    private String email;
    private String phoneNumber;
}
