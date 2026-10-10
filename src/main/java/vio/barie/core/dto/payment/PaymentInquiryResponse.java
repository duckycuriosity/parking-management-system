package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PaymentInquiryResponse {
    // 3 trường chung
    private String invoiceNo;
    private String status;
    private BigDecimal amount;
    // Chứa ca body
    private String metadata;
    // Subscription inquiries report the local verified outcome, not an unprocessed provider success.
    private String callbackType;
    private Long subscriptionId;
    private String resultCode;
    private String resultMessage;
}
