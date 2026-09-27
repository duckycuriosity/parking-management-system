package vio.barie.core.dto.system;

import lombok.Builder;

@Builder
public record RamMetricsResponse(
        double totalGb,
        double usedGb,
        double availableGb,
        double usagePercent) {
}
