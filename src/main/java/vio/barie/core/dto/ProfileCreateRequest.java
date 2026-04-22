package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProfileCreateRequest {

    @NotBlank(message = "Profile name cannot be empty")
    private String name;
}
