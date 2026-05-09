package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProfileCreateRequest {

    @NotBlank(message = "Profile name cannot be empty")
    private String name;

    @NotBlank(message = "Profile last face id cannot be empty")
    private String lastFaceId;

    @NotBlank(message = "Profile num face cannot be empty")
    private String numFace;

    @NotBlank(message = " Profile uuid cannot be empty")
    private String uuid;
}
