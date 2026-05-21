package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLaneResponse {
    private Long id;
    private String deviceId;
    private String status;
   
    private List<BarrierInfo> barriers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BarrierInfo {
        private Long id;
        private String barrierId;
        private String description;
        private String direction;
        private List<CameraInfo> cameras;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CameraInfo {
        private Long id;
        private String name;
        private String url;
        private String role; // Lấy từ bảng barrier_camera (plate, face)
        private String type;
    }
}
