package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import vio.barie.core.dto.BuildingCreateRequest;
import vio.barie.core.dto.BuildingResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.BuildingService;

import java.util.List;

@RestController
@RequestMapping("/api/buildings")
@RequiredArgsConstructor
@Tag(name = "Building Management", description = "CRUD APIs for buildings")
public class BuildingController {

    private final BuildingService buildingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<BuildingResponse> create(@Valid @RequestBody BuildingCreateRequest request) {
        return Result.ok("Building created successfully",
                buildingService.create(request));
    }

    @GetMapping
    public Result<List<BuildingResponse>> findAll() {
        return Result.ok("Building list fetched successfully",
                buildingService.findAll());
    }

    @GetMapping("/{id}")
    public Result<BuildingResponse> findById(@PathVariable Long id) {
        return Result.ok("Building fetched successfully",
                buildingService.findById(id));
    }

    @PutMapping("/{id}")
    public Result<BuildingResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody BuildingCreateRequest request) {
        return Result.ok("Building updated successfully",
                buildingService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        buildingService.delete(id);
        return Result.ok("Building deleted successfully", null);
    }
}
