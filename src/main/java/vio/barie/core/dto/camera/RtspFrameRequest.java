package vio.barie.core.dto.camera;

import jakarta.validation.constraints.NotBlank;

public record RtspFrameRequest(
        @NotBlank(message = "rtspUrl is required")
        String rtspUrl
) {
}
