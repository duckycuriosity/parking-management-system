package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.DeviceLaneResponse;
import vio.barie.core.dto.DeviceStatsResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.DeviceLaneService;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceLaneController {
    private final DeviceLaneService deviceLaneService;

    @GetMapping("/lane/{laneId}")
    public Result<List<DeviceLaneResponse>> getAllDeviceByLaneId(@PathVariable Long laneId) {
        return deviceLaneService.getAllDeviceByLaneId(laneId);
    }

    @GetMapping("/all")
    public Result<List<DeviceLaneResponse>> getAllDevice() {
        List<DeviceLaneResponse> data = deviceLaneService.getAllDevice();
        return Result.ok("Get All Device Successfully", data);
    }

    @GetMapping("/statistics")
    public Result<DeviceStatsResponse> getDeviceStatistics(
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "parkingLotId", required = false) Long parkingLotId) {
        DeviceStatsResponse data = deviceLaneService.getDeviceStats(buildingId, parkingLotId);
        return Result.ok("Get Device Statistics Successfully", data);
    }

}
