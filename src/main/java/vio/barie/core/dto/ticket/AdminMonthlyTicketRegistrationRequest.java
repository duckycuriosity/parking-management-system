package vio.barie.core.dto.ticket;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

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

    @NotBlank(message = "Ticket type required")
    @Pattern(regexp = "^(MONTH|QUARTER)$", message = "Ticket type must be MONTH or QUARTER")
    private String ticketType;

    @NotBlank(message = "Plate number required")
    private String vehiclePlateNumber;

    @NotBlank(message = "Vehicle type required")
    @Pattern(regexp = "^(CAR|MOTOBIKE|OTHER|MOTORBIKE|SEDAN|SUV|TRUCK|BIKE)$", message = "Invalid vehicle type")
    private String vehicleType;

    @NotBlank(message = "Plate type required")
    @Pattern(regexp = "^(NORMAL|DIPLOMATIC|OFFICIAL)$", message = "Invalid plate type")
    private String plateType;

    private String notes;
}
