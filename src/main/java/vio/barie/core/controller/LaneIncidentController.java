package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.LaneIncidentDecisionRequest;
import vio.barie.core.dto.LaneIncidentResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.LaneIncidentService;

import java.util.List;

@RestController
@RequestMapping("/api/lane-incidents")
@RequiredArgsConstructor
public class LaneIncidentController {
    private final LaneIncidentService laneIncidentService;

    @GetMapping("/pending")
    public Result<List<LaneIncidentResponse>> getPendingIncidents(@RequestParam(required = false) Long laneId) {
        List<LaneIncidentResponse> data = laneIncidentService.getPendingIncidents(laneId);
        return Result.ok("Fetched pending lane incidents successfully", data);
    }

    @PostMapping("/{incidentId}/approve")
    public Result<LaneIncidentResponse> approve(@PathVariable Long incidentId,
                                                @RequestBody(required = false) LaneIncidentDecisionRequest request) {
        LaneIncidentResponse data = laneIncidentService.approve(incidentId, request != null ? request.getNote() : null);
        return Result.ok("Đã xác nhận khuôn mặt, phiên gửi xe được đóng.", data);
    }

    @PostMapping("/{incidentId}/reject")
    public Result<LaneIncidentResponse> reject(@PathVariable Long incidentId,
                                               @RequestBody(required = false) LaneIncidentDecisionRequest request) {
        LaneIncidentResponse data = laneIncidentService.reject(incidentId, request != null ? request.getNote() : null);
        return Result.ok("Đã từ chối yêu cầu xác nhận khuôn mặt.", data);
    }
}
