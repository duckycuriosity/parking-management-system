package vio.barie.core.dto.camera;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CameraCreateRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String type = "stream-software";

    @NotBlank
    private String url;
}
