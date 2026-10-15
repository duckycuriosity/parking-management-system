package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BarrierManualOpenRequest {
    private String reasonOpenCmd;
}
