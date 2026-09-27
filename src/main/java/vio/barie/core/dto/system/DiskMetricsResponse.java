package vio.barie.core.dto.system;

import lombok.Builder;

@Builder
public record DiskMetricsResponse(
        String drive,
        double totalGb,
        double usedGb,
        double availableGb,
        double usagePercent) {
}
