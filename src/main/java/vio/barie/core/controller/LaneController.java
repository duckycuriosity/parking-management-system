package vio.barie.core.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.*;
import vio.barie.core.service.LaneService;
import lombok.extern.slf4j.Slf4j;
import vio.barie.core.service.ParkingLotService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;
import vio.barie.core.utils.DirectionType;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/lanes")
@RequiredArgsConstructor
@Tag(name = "Lane Management", description = "CRUD APIs for lanes")
public class LaneController {

    private final LaneService laneService;
    private final ParkingLotService parkingLotService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<LaneResponse> create(@Valid @RequestBody LaneCreateRequest request) {
        return Result.ok(
                "Lane created successfully",
                laneService.create(request)
        );
    }

    @GetMapping
    public Result<Page<LaneResponse>> findAll(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);

        return Result.ok("Lane list fetched successfully",
                laneService.findAll(pageable));
    }

    @GetMapping("/{id}")
    public Result<LaneResponse> findById(@PathVariable Long id) {
        return Result.ok(
                "Lane fetched successfully",
                laneService.findById(id)
        );
    }

    @GetMapping("/parking-lot/{parkingLotId}")
    public Result<List<LaneResponse>> findLanesByParkingLotId(@PathVariable Long parkingLotId, @RequestParam(name = "type", required = false) DirectionType type,
                                                              @RequestParam(value = "status", defaultValue = "ACTIVE", required = false) BaseStatus status) {
        return Result.ok(
                "Fetched lanes by parking lot successfully",
                laneService.findLanesByParkingLotId(parkingLotId, type, status)
        );
    }

    @PutMapping("/{id}")
    public Result<LaneResponse> update(@PathVariable Long id, @Valid @RequestBody LaneCreateRequest request) {
        return Result.ok("Lane updated successfully", laneService.update(id, request));
    }

    @PostMapping("/{id}/assign-cameras")
    public Result<Void> assignCameras(@PathVariable Long id, @RequestBody java.util.List<Long> cameraIds) {
        laneService.assignCameras(id, cameraIds);
        return Result.ok("Cameras assigned to lane",
                null);
    }

    @PostMapping("/{id}/assign-parking-devices")
    public Result<Void> assignParkingDevices(@PathVariable Long id,
                                             @RequestBody java.util.List<Long> deviceIds) {
        laneService.assignParkingDevices(id, deviceIds);
        return Result.ok("Parking devices assigned to lane",
                null
        );
    }

    @PostMapping("/{id}/assign-barrier-cameras")
    public Result<Void> assignBarrierCameras(@PathVariable Long id,
                                             @Valid @RequestBody java.util.List<BarrierCameraAssignmentRequest> requests) {
        laneService.assignBarrierCameras(id, requests);
        return Result.ok(
                "Barrier camera mappings assigned to lane",
                null
        );
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        laneService.delete(id);
        return Result.ok(
                "Lane deleted successfully",
                null
        );
    }
}