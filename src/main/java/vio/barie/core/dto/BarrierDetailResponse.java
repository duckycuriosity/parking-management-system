package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.utils.DirectionType;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class BarrierDetailResponse {
    private String barrierId;
    private String description;
    private DirectionType direction;
    private Long parkingDeviceId;
    private String deviceName; // Lấy từ parking_devices.device_id (ví dụ: Pk_abc1234a)
    private Long laneId;
    private String laneName;
    private String ipBarrier;
}
