package vio.barie.core.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.PlateCreateRequest;
import vio.barie.core.service.PlateService;

import java.util.List;

@RestController
@RequestMapping("/api/plates")
@RequiredArgsConstructor
@Tag(name = "Plate Management", description = "APIs for managing vehicle license plates")
public class PlateController {

    private final PlateService plateService;


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AiPlateResponse> create(
            @Valid @RequestBody PlateCreateRequest request) {
        return Result.ok(
                "Plate created successfully",
                plateService.create(request));
    }

    @GetMapping
    public Result<List<AiPlateResponse>> findAll() {
        return Result.ok("Plate list fetched successfully",
                plateService.findAll());
    }

    // Lấy thông tin theo ID (GET)
    @GetMapping("/{id}")
    public Result<AiPlateResponse> findById(@PathVariable Integer id) {
        return Result.ok(
                "Plate fetched successfully",
                plateService.findById(id));
    }

    @PutMapping("/{id}")
    public Result<AiPlateResponse> update(
            @PathVariable Integer id,
            @Valid @RequestBody PlateCreateRequest request) {
        return Result.ok("Plate updated successfully", plateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        plateService.delete(id); // Gọi hàm xóa trong service
        return Result.ok("Plate deleted successfully", null);
    }

}
