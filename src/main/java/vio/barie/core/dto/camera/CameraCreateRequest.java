package vio.barie.core.dto.camera;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CameraCreateRequest {
    @NotBlank
    private String name;

    @NotBlank
    private String type;
    private String url;
    private JsonNode extra;
}
