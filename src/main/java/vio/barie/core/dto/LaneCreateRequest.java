package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LaneCreateRequest {

    @NotBlank(message = "Lane name is required")
    @Size(max = 255, message = "Lane name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Lane type is required")
    @Size(max = 50, message = "Lane type must not exceed 50 characters")
    private String type;

    @NotBlank(message = "Lane status is required")
    @Size(max = 50, message = "Lane status must not exceed 50 characters")
    private String status;

    @NotNull(message = "parkingLotId is required")
    private Long parkingLotId;

    // optional grouping
    private java.util.List<Long> cameraIds;
    private java.util.List<Long> parkingDeviceIds;
}