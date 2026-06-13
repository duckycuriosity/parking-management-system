package vio.barie.core.dto.camera;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CameraUpdateRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String main;
    private String sub;
    private boolean enableRecord;
}
