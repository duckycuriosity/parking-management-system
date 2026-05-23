package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BarrierCameraAssignmentRequest {

    @NotBlank(message = "barrierId is required")
    private String barrierId;

    @NotNull(message = "cameraId is required")
    private Long cameraId;

    @NotBlank(message = "role is required")
    private String role;

    private Integer priority = 0;
}