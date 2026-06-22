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

    private Map<String, Long> sessionTypeDetails;
}
