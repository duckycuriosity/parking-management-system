package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class PaymentInquiryResponse {
    // 3 trường chung
    private String invoiceNo;
    private String status;
    private BigDecimal amount;
    // Chứa data riêng của megapay hoặc sepay
    private String metadata;
}
