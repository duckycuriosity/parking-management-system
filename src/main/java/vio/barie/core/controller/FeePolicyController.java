package vio.barie.core.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.fee.FeePolicyApplyRequest;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.service.FeePolicyService;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/fee-policies")
@RequiredArgsConstructor
@Tag(name = "Fee Policy Management", description = "Thiết lập biểu phí và tra cứu lịch sử hiệu lực")
public class FeePolicyController {

    private final FeePolicyService feePolicyService;

    @PostMapping("/apply")
    @Operation(summary = "Áp dụng cấu hình thành phiên bản biểu phí mới")
    public Result<FeePolicyResponse> apply(@Valid @RequestBody FeePolicyApplyRequest request) {
        return Result.ok("Áp dụng biểu phí thành công", feePolicyService.apply(request));
    }

    @GetMapping("/current")
    @Operation(summary = "Lấy biểu phí có hiệu lực tại thời điểm hiện tại")
    public Result<FeePolicyResponse> findCurrent(
            @RequestParam @Positive Long parkingLotId,
            @RequestParam VehicleType vehicleType) {
        return Result.ok("Lấy biểu phí hiện tại thành công",
                feePolicyService.findCurrent(parkingLotId, vehicleType));
    }

    @GetMapping("/at")
    @Operation(summary = "Lấy biểu phí có hiệu lực tại thời điểm chỉ định")
    public Result<FeePolicyResponse> findAt(
            @RequestParam @Positive Long parkingLotId,
            @RequestParam VehicleType vehicleType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return Result.ok("Lấy biểu phí theo thời điểm thành công",
                feePolicyService.findAt(parkingLotId, vehicleType, at));
    }

    @GetMapping("/history")
    @Operation(summary = "Lấy lịch sử phiên bản của bãi và loại phương tiện")
    public Result<List<FeePolicyResponse>> findHistory(
            @RequestParam @Positive Long parkingLotId,
            @RequestParam VehicleType vehicleType) {
        return Result.ok("Lấy lịch sử biểu phí thành công",
                feePolicyService.findHistory(parkingLotId, vehicleType));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy một phiên bản biểu phí cùng các khung giá")
    public Result<FeePolicyResponse> getById(@PathVariable @Positive Integer id) {
        return Result.ok("Lấy chi tiết biểu phí thành công", feePolicyService.getById(id));
    }
}
