package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class RevenueReportResponse {
    private BigDecimal totalRevenue;
    private BigDecimal totalReceivedAmount;
    private BigDecimal subscriptionAppliedAmount;
    private BigDecimal subscriptionReviewRequiredAmount;
    private long totalReceivedTransactions;
    private BigDecimal avgDailyRevenue;
    private long totalTransactions;
    private Page<DailyDetail> dailyDetails;

    @Data
    @Builder
    public static class DailyDetail {
        private String date;
        private long transactionCount;
        private BigDecimal revenue;
        private BigDecimal receivedAmount;
        private BigDecimal subscriptionAppliedAmount;
        private BigDecimal subscriptionReviewRequiredAmount;
        private long receivedTransactionCount;
    }
}
