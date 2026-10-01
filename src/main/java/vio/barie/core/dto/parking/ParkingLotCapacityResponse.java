package vio.barie.core.dto.parking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParkingLotCapacityResponse {
    private Long totalCapacity;
    private Long totalUsed;
    private Long totalAvailable;
    private Double usageRate;
    private List<ParkingLotCapacityItemResponse> lots;
}
