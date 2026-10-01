package vio.barie.core.dto.system;

import lombok.Builder;

@Builder
public record GpuMetricsResponse(
        String name,
        double vramTotalGb,
        double vramUsedGb,
        double usagePercent,
        double temperatureCelsius,
        double powerDrawWatts) {
}
