package vio.barie.core.dto.parking;

import lombok.Data;

@Data
public class ParkingMetricsResponse {
    private int currentCarsInParking;
    private int totalCarsExited;
    private int dailyTrafficCount;

}
