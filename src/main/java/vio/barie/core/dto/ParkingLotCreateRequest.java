package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import vio.barie.core.common.BaseStatus;

@Data
public class ParkingLotCreateRequest {

   
    @NotBlank(message = "Parking lot name is required")
    @Size(max = 255)
    private String name;

    @NotNull(message = "buildingId is required")
    private Long buildingId;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    @NotNull(message = "Status is required")
    private BaseStatus status;
}
