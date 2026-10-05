package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.LaneIncidentDecisionRequest;
import vio.barie.core.dto.LaneIncidentPlateRequest;
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

    @PostMapping("/{incidentId}/plate")
    public Result<LaneIncidentResponse> submitPlate(@PathVariable Long incidentId,
                                                    @Valid @RequestBody LaneIncidentPlateRequest request) {
        LaneIncidentResponse data = laneIncidentService.submitPlate(incidentId, request.getPlateNumber(), request.getNote());
        String message = switch (data.getOutcome()) {
            case LaneIncidentService.OUTCOME_SESSION_CLOSED -> "Đã tìm thấy phiên gửi xe theo biển số, phiên được đóng.";
            case LaneIncidentService.OUTCOME_FACE_MISMATCH ->
                    "Đã tìm thấy phiên gửi xe nhưng khuôn mặt lúc vào không khớp lúc ra, cần xác nhận khuôn mặt.";
            default -> "Vẫn không tìm thấy phiên gửi xe hợp lệ cho biển số này.";
        };
        return Result.ok(message, data);
    }

    @PostMapping("/{incidentId}/manual-open")
    public Result<LaneIncidentResponse> manualOpen(@PathVariable Long incidentId,
                                                   @RequestBody(required = false) LaneIncidentDecisionRequest request) {
        LaneIncidentResponse data = laneIncidentService.manualOpen(incidentId, request != null ? request.getNote() : null);
        return Result.ok("Đã gửi lệnh mở barrier thủ công.", data);
    }

    @PostMapping("/{incidentId}/reject")
    public Result<LaneIncidentResponse> reject(@PathVariable Long incidentId,
                                               @RequestBody(required = false) LaneIncidentDecisionRequest request) {
        LaneIncidentResponse data = laneIncidentService.reject(incidentId, request != null ? request.getNote() : null);
        return Result.ok("Đã từ chối yêu cầu xác nhận.", data);
    }
}
