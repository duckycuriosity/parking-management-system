package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.DeviceLaneResponse;
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
        List<DeviceLaneResponse> data = deviceLaneService.getAllDeviceByLaneId(laneId);
        return Result.ok("Thành công", data);
    }

    @GetMapping("/all")
    public Result<List<DeviceLaneResponse>> getAllDevice() {
        List<DeviceLaneResponse> data = deviceLaneService.getAllDevice();
        return Result.ok("Get All Device Successfully", data);
    }

}
