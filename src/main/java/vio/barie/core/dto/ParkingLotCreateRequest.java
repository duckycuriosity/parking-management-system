package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ParkingLotCreateRequest {

    @NotBlank(message = "Parking lot name is required")
    @Size(max = 255)
    private String name;

    @NotNull(message = "buildingId is required")
    private Long buildingId;
}
