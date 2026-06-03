package vio.barie.core.dto.ticket;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TicketSummaryResponse {
    private long total;        // ACTIVE + INACTIVE
    private long active;       // đang hoạt động
    private long expired;      // đã hết hạn (INACTIVE)
    private long expiringSoon; // sắp hết hạn (<= 5 ngày)
    private long dead;         // chưa thanh toán
}
