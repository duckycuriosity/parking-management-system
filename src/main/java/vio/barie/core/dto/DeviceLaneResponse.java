package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.common.RoleCamera;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLaneResponse {
    private Long id;
    private String deviceId;
    private OperatingStatus status;
    private String firmware_version;
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
        private RoleCamera role; // Lấy từ bảng barrier_camera (plate, face)
        private BaseStatus type;
    }
}
