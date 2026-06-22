package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.VehicleStatus;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DashboardStatsResponse {

    private long maxMotorcycleCapacity;
    private long maxCarCapacity;

    private VehicleStats vehicleStats;
    private boolean isFull;
    private String warningMessage;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class VehicleStats {
        private long totalMotorbike;
        private long totalCar;
        private long totalOther;
    }

}
