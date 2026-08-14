package vio.barie.core.dto.camera;

import lombok.Data;

@Data
public class CameraStreamResponse {
    private Long id;
    private String aiCameraId;
    private String streamUrl;
}
