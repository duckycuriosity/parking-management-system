package vio.barie.core.dto.camera;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

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
}
