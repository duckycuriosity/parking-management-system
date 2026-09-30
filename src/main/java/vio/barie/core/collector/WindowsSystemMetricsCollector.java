package vio.barie.core.collector;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import org.springframework.stereotype.Component;
import oshi.hardware.*;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import vio.barie.core.dto.system.CpuMetricsResponse;
import vio.barie.core.dto.system.DiskMetricsResponse;
import vio.barie.core.dto.system.GpuMetricsResponse;
import vio.barie.core.dto.system.RamMetricsResponse;

import java.util.ArrayList;
import java.util.List;
@Slf4j
@Component
// lấy thông số tại máy server đang chạy src code
public class WindowsSystemMetricsCollector {
    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hardware;

    public WindowsSystemMetricsCollector(){
        this.systemInfo = new SystemInfo();
        this.hardware = systemInfo.getHardware();
    }

    public CpuMetricsResponse collectCpu(){
        CentralProcessor processor =
                hardware.getProcessor();

        double usage = processor.getSystemCpuLoad(1000L) *100.0;
        double temperature = hardware.getSensors().getCpuTemperature();
        return CpuMetricsResponse.builder()
                .name(
                        processor.getProcessorIdentifier()
                                .getName()
                )
                .logicalProcessorCount(
                        processor.getLogicalProcessorCount()
                )
                .physicalProcessorCount(
                        processor.getPhysicalProcessorCount()
                )
                .usagePercent(round(usage))
                .temperatureCelsius(round(temperature))
                .build();
    }

    public RamMetricsResponse collectRam(){
        GlobalMemory memory = hardware.getMemory();
        long totalGb = memory.getTotal();
        long availableGb = memory.getAvailable();
        long usedGb = totalGb - availableGb;
        double usage = calculatePercent(
                usedGb,
                totalGb
        );
        return RamMetricsResponse.builder()
                .totalGb(bytesToGb(totalGb))
                .availableGb(bytesToGb(availableGb))
                .usedGb(bytesToGb(usedGb))
                .usagePercent(round(usage))
                .build();
    }

    public List<DiskMetricsResponse> collectDisks() {

        FileSystem fileSystem =
                systemInfo
                        .getOperatingSystem()
                        .getFileSystem();

        List<DiskMetricsResponse> result =
                new ArrayList<>();

        for (OSFileStore store :
                fileSystem.getFileStores()) {

            long totalGb =
                    store.getTotalSpace();

            long availableGb =
                    store.getUsableSpace();

            if (totalGb <= 0) {
                continue;
            }

            long usedGb =
                    totalGb - availableGb;

            double usage =
                    calculatePercent(
                            usedGb,
                            totalGb
                    );

            result.add(
                    DiskMetricsResponse.builder()
                            .drive(store.getMount())
                            .totalGb(bytesToGb(totalGb))
                            .usedGb(bytesToGb(usedGb))
                            .availableGb(bytesToGb(availableGb))
                            .usagePercent(round(usage))
                            .build()
            );
        }

        return result;
    }


    public List<GpuMetricsResponse> collectGpus() {

        List<GpuMetricsResponse> result =
                new ArrayList<>();

        List<GraphicsCard> graphicsCards =
                hardware.getGraphicsCards();
        for (GraphicsCard graphicsCard :
                graphicsCards) {
            // khi card chưa phải hoạt động nên -1
            double usagePercent = -1;
            double temperatureCelsius = -1;
            double powerDrawWatts = -1;
            long vramUsedBytes = -1;

            try (GpuStats stats =
                         graphicsCard.createStatsSession()) {

                usagePercent =
                        stats.getGpuUtilization();

                vramUsedBytes =
                        stats.getVramUsed();

                temperatureCelsius =
                        stats.getTemperature();

                powerDrawWatts =
                        stats.getPowerDraw();

            } catch (Exception e) {
                log.error(
                        "Không thể thu thập thông số GPU: {}",
                        graphicsCard.getName(), e
                );
            }
            result.add(
                    GpuMetricsResponse.builder()
                            .name(
                                    graphicsCard.getName()
                            )
                            .vramTotalGb(
                                    bytesToGb(graphicsCard.getVRam())
                            )
                            .vramUsedGb(bytesToGb(vramUsedBytes))
                            .usagePercent(round(usagePercent))
                            .temperatureCelsius(round(temperatureCelsius))
                            .powerDrawWatts(round(powerDrawWatts))
                            .build()
            );
        }

        return result;
    }

    private double calculatePercent(
            long used,
            long total
    ) {

        if (total <= 0) {
            return 0;
        }

        return ((double) used / total) * 100.0;
    }

    private double round(double value) {

        if (value < 0) {
            return value;
        }

        return Math.round(value * 100.0) / 100.0;
    }

    private double bytesToGb(long bytes) {

        if (bytes < 0) {
            return -1;
        }

        return Math.round(
                (bytes / (1024.0 * 1024.0 * 1024.0)) * 100
        ) / 100.0;
    }
}
