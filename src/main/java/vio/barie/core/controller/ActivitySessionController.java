package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.dto.BuildingResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.ParkingSessionService;

import java.util.List;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivitySessionController {
    private final ParkingSessionService parkingSessionService;

    @GetMapping("/lane/{laneId}/latest")
    public Result<List<ActivitySessionResponse>> getTop10LaneActivities(@PathVariable Long laneId) {
        List<ActivitySessionResponse> data = parkingSessionService.getTop10ActivitiesByLane(laneId);
        return Result.ok("Fetched top 10 recent activities successfully", data);
    }

    @GetMapping("/lane/{laneId}")
    public Result<Page<ActivitySessionResponse>> getAllLaneActivities(
            @PathVariable Long laneId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<ActivitySessionResponse> data = parkingSessionService.getActivitiesByLane(laneId, PageRequest.of(page, size));
        return Result.ok("Fetched all lane activities successfully", data);
    }
}
