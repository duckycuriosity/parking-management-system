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
        return new ApiResponse<>("SUCCESS", "Lane created successfully", laneService.create(request));
    }

    @GetMapping
    public ApiResponse<List<LaneResponse>> findAll() {
        return new ApiResponse<>("SUCCESS", "Lane list fetched successfully", laneService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<LaneResponse> findById(@PathVariable Long id) {
        return new ApiResponse<>("SUCCESS", "Lane fetched successfully", laneService.findById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<LaneResponse> update(@PathVariable Long id, @Valid @RequestBody LaneCreateRequest request) {
        return new ApiResponse<>("SUCCESS", "Lane updated successfully", laneService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        laneService.delete(id);
        return new ApiResponse<>("SUCCESS", "Lane deleted successfully", null);
    }
}