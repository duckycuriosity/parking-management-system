package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.common.BarrierActionType;
import vio.barie.core.dto.BarrierDetailResponse;
import vio.barie.core.dto.BarrierUpdateRequest;
import vio.barie.core.dto.Result;
import vio.barie.core.service.BarrierService;
import vio.barie.core.service.mqtt.ParkingMessageService;

import java.util.List;

@RestController
@RequestMapping("/api/barriers")
@RequiredArgsConstructor
public class BarrierController {
    private final BarrierService barrierService;
    private final ParkingMessageService parkingMessageService;

    @PostMapping("/{barrierId}/manual-open")
    public Result<Void> manualOpenBarrier(@PathVariable String barrierId) {
        parkingMessageService.publishManualOpenCommand(barrierId);
        return Result.ok("Đã gửi lệnh mở barrier thủ công thành công.", null);
    }


    @GetMapping("/all")
    public Result<List<BarrierDetailResponse>> getAllBarrierDetails() {
        List<BarrierDetailResponse> response = barrierService.getAllBarrierDetails();
        return Result.ok("Get a detailed list of successful barrier installations.", response);
    }

    @GetMapping("/{barrierId}")
    public Result<BarrierDetailResponse> getBarrierById(@PathVariable String barrierId) {

        BarrierDetailResponse response = barrierService.getBarrierDetailById(barrierId);

        return Result.ok("Get barrier id details successfully.", response);
    }

    @PutMapping("/{barrierId}")
    public Result<BarrierDetailResponse> updateBarrier(
            @PathVariable String barrierId,
            @Valid @RequestBody BarrierUpdateRequest request) {

        BarrierDetailResponse response = barrierService.updateBarrier(barrierId, request);
        return Result.ok("Barrier information updated successfully.", response);
    }

    @DeleteMapping("reset/{barrierId}")
    public Result<Void> resetBarrier(@PathVariable String barrierId) {

        barrierService.resetBarrierConfiguration(barrierId);

        return Result.ok("Reset the barrier successfully to default.", null);
    }


}
