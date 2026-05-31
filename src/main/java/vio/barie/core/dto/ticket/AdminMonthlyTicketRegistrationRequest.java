package vio.barie.core.dto.ticket;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.common.TicketType;

import java.time.LocalDateTime;

@Data
public class AdminMonthlyTicketRegistrationRequest {

    @NotBlank(message = "Customer name required")
    private String name;

    private Integer groupId = 0;

    @NotBlank(message = "Phone number required")
    @Pattern(regexp = "^0\\d{9}$", message = "Invalid phone format (must be 10 digits starting with 0)")
    private String customerPhone;

    @Email(message = "Invalid email format")
    private String customerEmail;

    private String customerZaloId;

    @NotNull(message = "Ticket type required")
    private TicketType ticketType;

    @NotBlank(message = "Plate number required")
    private String vehiclePlateNumber;

    @NotBlank(message = "Vehicle type required")
    @Pattern(regexp = "^(CAR|MOTOBIKE|OTHER|MOTORBIKE|SEDAN|SUV|TRUCK|BIKE)$", message = "Invalid vehicle type")
    private String vehicleType;

    @NotBlank(message = "Plate type required")
    @Pattern(regexp = "^(NORMAL|DIPLOMATIC|OFFICIAL)$", message = "Invalid plate type")
    private String plateType;

    private String notes;


//    private TicketStatus status;
}
