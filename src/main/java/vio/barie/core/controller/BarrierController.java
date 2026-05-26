package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.BarrierDetailResponse;
import vio.barie.core.dto.BarrierUpdateRequest;
import vio.barie.core.dto.Result;
import vio.barie.core.service.BarrierService;

import java.util.List;

@RestController
@RequestMapping("/api/barriers")
@RequiredArgsConstructor
public class BarrierController {
    private final BarrierService barrierService;

    @GetMapping("/details")
    public Result<List<BarrierDetailResponse>> getAllBarrierDetails() {
        List<BarrierDetailResponse> response = barrierService.getAllBarrierDetails();
        return Result.ok("Get a detailed list of successful barrier installations.", response);
    }

    @PutMapping("/{barrierId}")
    public Result<Void> updateBarrier(
            @PathVariable String barrierId,
            @Valid @RequestBody BarrierUpdateRequest request) {

        barrierService.updateBarrier(barrierId, request);

        return Result.ok("Barrier information updated successfully.", null);
    }

    @DeleteMapping("/{barrierId}")
    public Result<Void> resetBarrier(@PathVariable String barrierId) {

        barrierService.resetBarrierConfiguration(barrierId);

        return Result.ok("Reset the barrier successfully to default.", null);
    }

}
