package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import vio.barie.core.common.OperatingStatus;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.parking.HourlyTrafficResponse;
import vio.barie.core.dto.parking.ParkingMetricsRequest;
import vio.barie.core.dto.parking.VehicleMetricsResponse;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.entity.ParkingMessageEntity;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.repository.ParkingMessageRepository;
import vio.barie.core.service.ParkingSessionService;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/parking-monitor")
@RequiredArgsConstructor
public class ParkingMonitorController {
    private final ParkingMessageRepository messageRepository;
    private final ParkingDeviceRepository deviceRepository;
    private final ParkingSessionService parkingSessionService;

    @GetMapping("/devices")
    public List<ParkingDeviceEntity> devices() {
        return deviceRepository.findAll();
    }

    @GetMapping("/devices/online")
    public List<ParkingDeviceEntity> onlineDevices() {
        return deviceRepository.findByStatus(OperatingStatus.ONLINE);
    }

    @GetMapping("/messages/latest")
    public List<ParkingMessageEntity> latestMessages() {
        return messageRepository.findTop100ByOrderByCreatedAtDesc();
    }


    @GetMapping("/messages/{deviceId}")
    public List<ParkingMessageEntity> deviceMessages(
            @PathVariable String deviceId
    ) {
        return messageRepository
                .findTop50ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    @GetMapping("/parking/vehicle-metrics")
    public Result<VehicleMetricsResponse> parkingMetrics (
            @RequestParam(required = false) Integer buildingId
    ) {
        VehicleMetricsResponse response = parkingSessionService.getVehicleMetrics(buildingId);
        return Result.ok(response);
    }

    @GetMapping("/parking/hourly-traffic")
    public Result<List<HourlyTrafficResponse>> getHourlyTraffic (@RequestParam(required = false) Integer buildingId,
                                                                 @RequestParam LocalDateTime startDate,
                                                                 @RequestParam LocalDateTime endDate) {
        ParkingMetricsRequest request = new ParkingMetricsRequest();
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setBuildingId(buildingId);
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date must be after start date");
        }
        if (ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) > 30) {
            throw new IllegalArgumentException("Date range cannot exceed 30 days");
        }
        List<HourlyTrafficResponse> response = parkingSessionService.getHourlyStatistics(request);
        return Result.ok(response);
    }

}
