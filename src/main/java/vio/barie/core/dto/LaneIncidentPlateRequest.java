package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LaneIncidentPlateRequest {
    @NotBlank
    private String plateNumber;
    private String note;
}
