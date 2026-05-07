package vio.barie.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.vms.LiveStreamRequestDTO;
import vio.barie.core.service.CameraService;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class CameraController {
    private final CameraService cameraService;

    @PostMapping("/create")
    public ResponseEntity<JsonNode> createCamera(@RequestBody JsonNode clientBody) {
        JsonNode createdCamera = cameraService.createCamera(clientBody);
        return ResponseEntity.ok(createdCamera);
    }

    @PostMapping("/start")
    public ResponseEntity<JsonNode> startLiveStream(@Valid @RequestBody LiveStreamRequestDTO request) {
        JsonNode response = cameraService.startLiveSteam(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/list")
    public ResponseEntity<JsonNode> getCameraList() {
        JsonNode response = cameraService.getCameraList();
        return ResponseEntity.ok(response);
    }

}
