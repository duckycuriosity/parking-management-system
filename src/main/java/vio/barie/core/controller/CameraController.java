package vio.barie.core.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.ReportLinkResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.StreamResponse;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import vio.barie.core.dto.camera.CameraUpdateRequest;
import vio.barie.core.service.CameraService;
import vio.barie.core.service.RtspFrameService;
import vio.barie.core.dto.camera.RtspFrameRequest;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/camera")
@RequiredArgsConstructor
public class CameraController {
    private final CameraService cameraService;
    private final RtspFrameService rtspFrameService;

    @PostMapping(value = "/frame", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> captureFrame(@Valid @RequestBody RtspFrameRequest request) {
        try {
            log.info("frame captured for RTSP URL: {}", request.rtspUrl());
            byte[] frame = rtspFrameService.capture(request.rtspUrl());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(frame);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @PostMapping("/create")
    public Result<CameraResponse> createCamera(@RequestBody CameraCreateRequest clientBody) {
        CameraResponse createdCamera = cameraService.createCamera(clientBody);
        return Result.ok("Camera created successfully",
                createdCamera);
    }

    @PostMapping("/sync")
    public Result<List<CameraResponse>> syncCameras() {
        List<CameraResponse> syncedList = cameraService.syncCamerasFromAI();
        return Result.ok("Synchronized cameras from VMS successfully", syncedList);
    }

    @GetMapping
    public Result<List<CameraResponse>> getCameraList(@RequestParam(name = "searchKey", required = false) String searchKey,
                                                      @RequestParam(name = "status", required = false) BaseStatus status) {
        List<CameraResponse> response = cameraService.getCameraList(searchKey, status);
        return Result.ok("Get list cameras successfully", response);
    }

    @GetMapping("/{id}")
    public Result<CameraResponse> getCameraById(@PathVariable Long id) {
        CameraResponse response = cameraService.getCameraById(id);
        return Result.ok("Get camera successfully", response);
    }

    @PutMapping("/{id}")
    public Result<CameraResponse> updateCamera(
            @PathVariable Long id,
            @RequestBody CameraUpdateRequest request) {
        CameraResponse response = cameraService.updateCamera(id, request);
        return Result.ok("Camera updated successfully", response);
    }

    @DeleteMapping("/{id}")
    public Result<CameraResponse> deleteCamera(@PathVariable Long id) {
        cameraService.deleteCamera(id);
        return Result.ok("Camera deleted successfully", null);
    }

    @GetMapping("/stream/{aiCameraId}")
    public void getLiveStream(@PathVariable("aiCameraId") String aiCameraId, HttpServletResponse response) {
        cameraService.aiVideoStream(aiCameraId, response);
    }
//
//    @GetMapping("/{id}/report-link")
//    public Result<ReportLinkResponse> getReportLink(@PathVariable("id") Integer identityId) {
//        ReportLinkResponse response = cameraService.getReportLink(identityId);
//        return Result.ok("Get report link successfully", response);
//    }

}
