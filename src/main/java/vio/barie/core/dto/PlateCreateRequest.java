package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PlateCreateRequest {
    @NotBlank(message = "Plate number cannot be empty")
    private String plate;

    @NotBlank(message = "Plate type cannot be empty")
    private String type;

    @NotNull(message = "Enabled flag cannot be null")
    private Boolean enabled;
}
