package vio.barie.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.Result;
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
    public Result<CameraResponse> createCamera(@RequestBody CameraCreateRequest clientBody) {
        CameraResponse createdCamera = cameraService.createCamera(clientBody);
        return Result.ok("Camera created successfully",
                createdCamera);
    }

//    @PostMapping("/start")
//    public ResponseEntity<JsonNode> startLiveStream(@Valid @RequestBody LiveStreamRequestDTO request) {
//        JsonNode response = cameraService.startLiveSteam(request);
//        return ResponseEntity.ok(response);
//    }

    @GetMapping("/list")
    public Result<List<CameraResponse>> getCameraList() {
        List<CameraResponse> response = cameraService.getCameraList();
        return Result.ok("Get list cameras successfully",
                response
        );
    }

}
