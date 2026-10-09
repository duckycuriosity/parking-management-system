package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import vio.barie.core.common.BaseStatus;

@Data
public class BuildingCreateRequest {

    @NotBlank(message = "Building name is required")
    @Size(max = 255, message = "Building name must not exceed 255 characters")
    private String name;

    @Size(max = 2000, message = "Address must not exceed 2000 characters")
    private String address;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    @NotNull(message = "Status is required")
    private BaseStatus status;

    @Size(max = 10, message = "Phone number must not exceed 10 characters")
    private String phone_number;

    @Size(max = 200, message = "Management unit must not exceed 10 characters")
    private String management_unit;

}