package vio.barie.core.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeviceStatsResponse {
    private long totalCamera;
    private long onlineCamera;
    private long offlineCamera;
    private long totalBarrier;
    private long onlineBarrier;
    private long offlineBarrier;
    private long totalPi;
    private long onlinePi;
    private long offlinePi;
    private Double activePercentage;
}
