package vio.barie.core.dto.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RefundRequest {

    @NotBlank(message = "trxId is required")
    private String trxId;           // trxId gốc từ MegaPay (gatewayTrxId)

    @NotBlank(message = "internalRefId is required")
    private String internalRefId;   // merTrxId gốc (internalRefId)

    @NotNull
    @Min(value = 1, message = "Amount must be > 0")
    private BigDecimal amount;

    @NotBlank(message = "payType is required")
    private String payType;         // IC, DC, EW, IS, QR, PL, CW

    @NotBlank(message = "cancelMsg is required")
    private String cancelMsg;       // Lý do hoàn tiền
}
