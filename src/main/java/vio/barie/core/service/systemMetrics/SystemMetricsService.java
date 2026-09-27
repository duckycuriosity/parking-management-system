package vio.barie.core.service.systemMetrics;

import vio.barie.core.dto.system.SystemMetricsResponse;

public interface SystemMetricsService {
    SystemMetricsResponse getMetrics();

    void refreshMetrics();
}
