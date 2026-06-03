package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import vio.barie.core.utils.DirectionType;

@Data
public class BarrierUpdateRequest {
    private Long faceCameraId;
    private Long plateCameraId;

    @NotNull(message = "Please choose the control device")
    private Long parkingDeviceId;
    @NotBlank(message = "Please choose the control device")
    private String deviceName;
    @NotNull(message = "Please select your assigned lane.")
    private Long laneId;

    @NotNull(message = "Vui lòng chọn chiều của Barie")
    private DirectionType direction;

    private String description;
}
