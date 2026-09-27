package vio.barie.core.dto.system;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
@Builder
public record SystemMetricsResponse(
        OffsetDateTime timestamp,
        String hostname,
        CpuMetricsResponse cpu,
        RamMetricsResponse ram,
        List<DiskMetricsResponse> disks,
        List<GpuMetricsResponse> gpus) {
}
