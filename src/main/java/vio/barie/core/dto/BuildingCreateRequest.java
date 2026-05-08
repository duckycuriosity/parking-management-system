package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BuildingCreateRequest {

    @NotBlank(message = "Building name is required")
    @Size(max = 255, message = "Building name must not exceed 255 characters")
    private String name;

    @Size(max = 2000, message = "Address must not exceed 2000 characters")
    private String address;
}