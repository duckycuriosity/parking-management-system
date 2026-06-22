package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PaymentListItemResponse {
    private String plateNumber;
    private String type;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime time;
}
