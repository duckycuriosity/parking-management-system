package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class RevenueReportResponse {
    private BigDecimal totalRevenue;
    private BigDecimal avgDailyRevenue;
    private long totalTransactions;
    private List<DailyDetail> dailyDetails;

    @Data
    @Builder
    public static class DailyDetail {
        private String date;
        private long transactionCount;
        private BigDecimal revenue;
    }
}
