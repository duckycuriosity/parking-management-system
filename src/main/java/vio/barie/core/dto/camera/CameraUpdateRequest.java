package vio.barie.core.dto.camera;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import vio.barie.core.common.AICameraMode;

@Data
public class CameraUpdateRequest {
    @NotBlank
    private String name;

    @NotBlank
    @JsonAlias("url")
    private String main;
    private String sub;
    //    private boolean enableRecord;
    private Double latitude;
    private Double longitude;
    private CameraRoi roi;
    private AICameraMode mode;
    private String protocol;
}
