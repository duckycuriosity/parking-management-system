package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.utils.DirectionType;

@Data
public class LaneCreateRequest {

    @NotBlank(message = "Lane name is required")
    @Size(max = 255, message = "Lane name must not exceed 255 characters")
    private String name;


    @NotNull(message = "Lane type is required")
    private DirectionType type;


    @NotNull(message = "Lane status is required")
    private BaseStatus status;

    @NotNull(message = "parkingLotId is required")
    private Long parkingLotId;

    // optional grouping
    private java.util.List<Long> cameraIds;
    private java.util.List<Long> parkingDeviceIds;
}