package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class RevenueSummaryResponse {
    private BigDecimal totalRevenue;
    private long successfulTransactions;
}
