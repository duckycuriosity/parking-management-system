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
import vio.barie.core.dto.ApiResponse;
import vio.barie.core.dto.LaneCreateRequest;
import vio.barie.core.dto.LaneResponse;
import vio.barie.core.service.LaneService;

import java.util.List;

@RestController
@RequestMapping("/api/lanes")
@RequiredArgsConstructor
@Tag(name = "Lane Management", description = "CRUD APIs for lanes")
public class LaneController {

    private final LaneService laneService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LaneResponse> create(@Valid @RequestBody LaneCreateRequest request) {
        ApiResponse<LaneResponse> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Lane created successfully");
        resp.setData(laneService.create(request));
        return resp;
    }

    @GetMapping
    public ApiResponse<List<LaneResponse>> findAll() {
        ApiResponse<java.util.List<LaneResponse>> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Lane list fetched successfully");
        resp.setData(laneService.findAll());
        return resp;
    }

    @GetMapping("/{id}")
    public ApiResponse<LaneResponse> findById(@PathVariable Long id) {
        ApiResponse<LaneResponse> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Lane fetched successfully");
        resp.setData(laneService.findById(id));
        return resp;
    }

    @PutMapping("/{id}")
    public ApiResponse<LaneResponse> update(@PathVariable Long id, @Valid @RequestBody LaneCreateRequest request) {
        ApiResponse<LaneResponse> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Lane updated successfully");
        resp.setData(laneService.update(id, request));
        return resp;
    }

    @PostMapping("/{id}/assign-cameras")
    public ApiResponse<String> assignCameras(@PathVariable Long id, @RequestBody java.util.List<Long> cameraIds) {
        laneService.assignCameras(id, cameraIds);
        ApiResponse<String> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Cameras assigned to lane");
        resp.setData(null);
        return resp;
    }

    @PostMapping("/{id}/assign-parking-devices")
    public ApiResponse<String> assignParkingDevices(@PathVariable Long id,
            @RequestBody java.util.List<Long> deviceIds) {
        laneService.assignParkingDevices(id, deviceIds);
        ApiResponse<String> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Parking devices assigned to lane");
        resp.setData(null);
        return resp;
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        laneService.delete(id);
        ApiResponse<String> resp = new ApiResponse<>();
        resp.setCode("SUCCESS");
        resp.setMessage("Lane deleted successfully");
        resp.setData(null);
        return resp;
    }
}