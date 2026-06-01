package vio.barie.core.dto.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import vio.barie.core.common.TicketType;

import java.time.LocalDateTime;

@Data
public class RenewTicketRequest {

    @NotNull(message = "id required")
    private Long id;

    @NotNull(message = "Start date required")
    private LocalDateTime startDate;

    @NotNull(message = "Ticket type required")
    private TicketType ticketType;

    private String notes;
}