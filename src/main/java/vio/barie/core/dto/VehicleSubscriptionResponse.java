package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleSubscriptionResponse {
    private Long subscriptionId;
    private String plateNumber;
    private String subscriberName;
    private String parkingLotName;
    private LocalDateTime expirationDate;
    private String ticketType;
    private String status;
}
