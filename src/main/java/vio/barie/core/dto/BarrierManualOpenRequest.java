package vio.barie.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BarrierManualOpenRequest {
    @NotBlank(message = "Lý do mở barrier không được để trống")
    @Size(max = 255, message = "Lý do mở barrier không được vượt quá 255 ký tự")
    private String reasonOpenCmd;
}
