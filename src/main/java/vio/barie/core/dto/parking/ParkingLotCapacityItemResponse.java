package vio.barie.core.dto.parking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParkingLotCapacityItemResponse {
    private Long parkingLotId;
    private String parkingLotName;
    private Long totalCapacity;
    private Long used;
    private Long available;
    private Double usageRate;
    private Long motorbikeCapacity;
    private Long carCapacity;
    private Long usedMotorbike;
    private Long usedCar;
    private Long usedOther;
}
