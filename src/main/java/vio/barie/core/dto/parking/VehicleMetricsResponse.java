package vio.barie.core.dto.parking;

import lombok.Data;

@Data
public class VehicleMetricsResponse {
    private Integer cars;
    private Integer motorbikes;
    private Integer unidentifiedVehicles;
}
