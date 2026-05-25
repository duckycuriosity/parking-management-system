package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.FlowType;

@Data
public class LaneResponse {
    private Long id;
    private String name;
    private FlowType type;
    private BaseStatus status;
    private Long parkingLotId;
    private java.time.LocalDateTime createdAt;
    private java.util.List<Long> cameraIds;
    private java.util.List<Long> parkingDeviceIds;
}