package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.ParkingLotCreateRequest;
import vio.barie.core.dto.ParkingLotResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.ParkingLotService;

import java.util.List;

@RestController
@RequestMapping("/api/parking-lots")
@RequiredArgsConstructor
@Tag(name = "Parking Lot Management", description = "CRUD APIs for parking lots")
public class ParkingLotController {

    private final ParkingLotService parkingLotService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<ParkingLotResponse> create(@Valid @RequestBody ParkingLotCreateRequest request) {
        return Result.ok("Parking lot created", parkingLotService.create(request));
    }

    @GetMapping
    public Result<List<ParkingLotResponse>> findAll() {
        return Result.ok("Parking lots fetched", parkingLotService.findAll());
    }

    @GetMapping("/{id}")
    public Result<ParkingLotResponse> findById(@PathVariable Long id) {
        return Result.ok("Parking lot fetched", parkingLotService.findById(id));
    }

    @PutMapping("/{id}")
    public Result<ParkingLotResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody ParkingLotCreateRequest request) {
        return Result.ok("Parking lot updated", parkingLotService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        parkingLotService.delete(id);
        return Result.ok("Parking lot deleted", null);
    }
}
