package vio.barie.core.dto.camera;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import vio.barie.core.common.AICameraMode;

import java.util.Map;

@Data
public class CameraCreateRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String main;
    private String sub;
    private boolean enableRecord;
    private Double latitude;
    private Double longitude;

    @NotNull(message = "Mode is not allowed to blank")
    private AICameraMode mode;
    private String protocol;
}
