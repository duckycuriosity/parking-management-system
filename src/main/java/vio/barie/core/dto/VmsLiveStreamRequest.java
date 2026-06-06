package vio.barie.core.dto;

import lombok.Data;

@Data
public class VmsLiveStreamRequest {
    private Long cameraId; // Đây là ID của VMS (aiCameraId), không phải ID DB
    private Integer channel;
}
