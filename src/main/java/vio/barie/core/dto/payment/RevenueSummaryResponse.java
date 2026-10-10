package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class RevenueSummaryResponse {
    private BigDecimal totalRevenue;
    /** Legacy PAID amounts plus APPLIED and REVIEW_REQUIRED subscription cash; VND only. */
    private BigDecimal totalReceivedAmount;
    private BigDecimal subscriptionAppliedAmount;
    private BigDecimal subscriptionReviewRequiredAmount;
    private long subscriptionReceivedTransactions;
    private long successfulTransactions;
    private long pendingTransactions;
    private long failedTransactions;
}
