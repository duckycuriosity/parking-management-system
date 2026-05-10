package vio.barie.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.ApiResponse;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import vio.barie.core.dto.vms.LiveStreamRequestDTO;
import vio.barie.core.service.CameraService;

import java.util.List;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class CameraController {
    private final CameraService cameraService;

    @PostMapping("/create")
    public ApiResponse<CameraResponse> createCamera(@RequestBody CameraCreateRequest clientBody) {
        CameraResponse createdCamera = cameraService.createCamera(clientBody);
        // return ResponseEntity.ok(createdCamera);
        return new ApiResponse<>(
                "SUCCESS",
                "Camera created successfully",
                createdCamera);
    }

//    @PostMapping("/start")
//    public ResponseEntity<JsonNode> startLiveStream(@Valid @RequestBody LiveStreamRequestDTO request) {
//        JsonNode response = cameraService.startLiveSteam(request);
//        return ResponseEntity.ok(response);
//    }

    @GetMapping("/list")
    public ApiResponse<List<CameraResponse>> getCameraList() {
        List<CameraResponse> response = cameraService.getCameraList();
        return new ApiResponse<>(
                "SUCCESS",
                "Get list cameras",
                response
        );
    }

}
