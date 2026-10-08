package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.system.SystemMetricsResponse;
import vio.barie.core.service.systemMetrics.SystemMetricsService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/system")
public class SystemMetricsController {
    private final SystemMetricsService systemMetricsService;

    @GetMapping("/metrics")
    public SystemMetricsResponse getMetrics() {

        return systemMetricsService.getMetrics();
    }
}
