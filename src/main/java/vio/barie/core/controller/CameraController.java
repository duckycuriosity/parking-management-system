package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import vio.barie.core.service.CameraService;

import java.util.List;

@RestController
@RequestMapping("/api/camera")
@RequiredArgsConstructor
public class CameraController {
    private final CameraService cameraService;

    @PostMapping("/create")
    public Result<CameraResponse> createCamera(@RequestBody CameraCreateRequest clientBody) {
        CameraResponse createdCamera = cameraService.createCamera(clientBody);
        return Result.ok("Camera created successfully",
                createdCamera);
    }


    @GetMapping
    public Result<List<CameraResponse>> getCameraList() {
        List<CameraResponse> response = cameraService.getCameraList();
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
            @RequestBody CameraCreateRequest request) {
        CameraResponse response = cameraService.updateCamera(id, request);
        return Result.ok("Camera updated successfully", response);
    }

    @DeleteMapping("/{id}")
    public Result<CameraResponse> deleteCamera(@PathVariable Long id) {
        cameraService.deleteCamera(id);
        return Result.ok("Camera deleted successfully", null);
    }

}
