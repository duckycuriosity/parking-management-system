package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import vio.barie.core.utils.DirectionType;

@Data
public class BarrierUpdateRequest {

//    @NotNull(message = "Please choose the control device")
//    private Long parkingDeviceId;
//    @NotBlank(message = "Please choose the control device")
//    private String deviceName;

    @NotNull(message = "Vui lòng chọn chiều của Barie")
    private DirectionType direction;
    private String ipBarrier;
    private String description;
}
