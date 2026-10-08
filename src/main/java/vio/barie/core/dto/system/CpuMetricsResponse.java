package vio.barie.core.dto.system;

import lombok.Builder;

@Builder
public record CpuMetricsResponse(
        String name,
        int logicalProcessorCount,
        int physicalProcessorCount,
        double usagePercent,
        double temperatureCelsius
) {
}
