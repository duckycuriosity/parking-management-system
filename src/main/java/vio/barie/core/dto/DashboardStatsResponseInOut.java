package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DashboardStatsResponseInOut {
    private long totalTraffic;
    private long totalExit;
    private long totalEntry;

    private long totalSessionTypeCount;
    private long totalSessionDay;
    private long totalSessionMonth;
    private long totalSessionQuarter;
    private long dayCar;
    private long dayMotorbike;
    private long monthCar;
    private long monthMotorbike;
    private long quarterCar;
    private long quarterMotorbike;
}
