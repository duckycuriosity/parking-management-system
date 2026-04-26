package vio.barie.core.dto.ticket;

import lombok.Data;
import lombok.NonNull;
import java.time.LocalDateTime;

@Data
public class TicketRegisterRequest {
    @NonNull
    private Long vehicleId;
    private int duration;
    private String type; // 'DAY', 'MONTH', 'YEAR'
    private LocalDateTime registerAt;
    private Long paymentId;
}
