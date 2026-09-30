package vio.barie.core.service.systemMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vio.barie.core.collector.WindowsSystemMetricsCollector;
import vio.barie.core.dto.system.SystemMetricsResponse;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemMetricsMonitoringService implements SystemMetricsService{
    private final WindowsSystemMetricsCollector collector;

    private volatile SystemMetricsResponse latestMetrics;


    @Override
    public SystemMetricsResponse getMetrics() {
        return latestMetrics;
    }

    @Override
    @Scheduled(
            fixedDelayString = "${system.metrics.collection-interval-ms:3000}"
    )
    public void refreshMetrics() {

        try {

            String hostname =
                    InetAddress
                            .getLocalHost()
                            .getHostName();

            latestMetrics =
                    SystemMetricsResponse.builder()
                            .timestamp(
                                    OffsetDateTime.now(
                                            ZoneOffset.UTC
                                    )
                            )
                            .hostname(hostname)
                            .cpu(
                                    collector.collectCpu()
                            )
                            .ram(
                                    collector.collectRam()
                            )
                            .disks(
                                    collector.collectDisks()
                            )
                            .gpus(
                                    collector.collectGpus()
                            )
                            .build();

        } catch (Exception e) {

            log.error(
                    "Không thể thu thập các chỉ số hệ thống", e
            );
        }
    }
}
