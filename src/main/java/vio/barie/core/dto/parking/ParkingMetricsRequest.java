package vio.barie.core.dto.parking;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ParkingMetricsRequest {
    @NotNull(message = "Start date is required")
    LocalDateTime startDate;
    @NotNull(message = "Start date is required")
    LocalDateTime endDate;
    private Integer buildingId;
}
