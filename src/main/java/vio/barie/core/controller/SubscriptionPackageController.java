package vio.barie.core.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.subscription.SubscriptionRequests.CreateSubscriptionPackageRequest;
import vio.barie.core.dto.subscription.SubscriptionRequests.SubscriptionPackageSaleRequest;
import vio.barie.core.dto.subscription.SubscriptionRequests.SubscriptionPackageTermsRequest;
import vio.barie.core.dto.subscription.SubscriptionResponses.SubscriptionPackageResponse;
import vio.barie.core.service.SubscriptionPackageService;
import vio.barie.core.utils.VehicleType;

import java.util.List;

/** Catalog and explicit JPA draft/publication operations. */
@RestController
@RequestMapping("/api/subscription-packages")
@RequiredArgsConstructor
@Tag(name = "Subscription Package Catalog", description = "Danh sách phiên bản gói đang được mở bán")
public class SubscriptionPackageController {
    private final SubscriptionPackageService packageService;

    @GetMapping
    //@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Lấy các gói đang mở bán tại bãi theo loại phương tiện")
    public Result<List<SubscriptionPackageResponse>> availablePackages(
            @RequestParam @Positive Long parkingLotId,
            @RequestParam VehicleType vehicleType) {
        return Result.ok("Lấy danh sách gói thuê bao thành công",
                packageService.availablePackages(parkingLotId, vehicleType));
    }

    @PostMapping
    //PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @Operation(summary = "Tạo phiên bản gói nháp; backend tự cấp số phiên bản")
    public Result<SubscriptionPackageResponse> create(@Valid @RequestBody CreateSubscriptionPackageRequest request) {
        return Result.ok("Tạo gói nháp thành công", packageService.createDraft(request));
    }

    @PutMapping("/{id}/draft")
    //@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @Operation(summary = "Sửa quyền lợi và thay toàn bộ lịch của phiên bản còn nháp")
    public Result<SubscriptionPackageResponse> updateDraft(@PathVariable @Positive Long id,
                                                          @Valid @RequestBody SubscriptionPackageTermsRequest request) {
        return Result.ok("Cập nhật gói nháp thành công", packageService.updateDraft(id, request));
    }

    @PostMapping("/{id}/publish")
    //@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @Operation(summary = "Công bố gói sau khi kiểm tra lịch và hiệu lực bán")
    public Result<SubscriptionPackageResponse> publish(@PathVariable @Positive Long id) {
        return Result.ok("Công bố gói thuê bao thành công", packageService.publish(id));
    }

    @PutMapping("/{id}/sale")
    //@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @Operation(summary = "Bật/tắt bán hoặc đổi mốc kết thúc bán của phiên bản đã công bố")
    public Result<SubscriptionPackageResponse> updateSale(@PathVariable @Positive Long id,
                                                         @Valid @RequestBody SubscriptionPackageSaleRequest request) {
        return Result.ok("Cập nhật hiệu lực bán thành công", packageService.updateSale(id, request));
    }

    @GetMapping("/history")
    //@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public Result<List<SubscriptionPackageResponse>> history(@RequestParam String packageCode) {
        return Result.ok("Lấy lịch sử gói thuê bao thành công", packageService.history(packageCode));
    }

    @GetMapping("/{id}")
    //@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public Result<SubscriptionPackageResponse> get(@PathVariable @Positive Long id) {
        return Result.ok("Lấy chi tiết gói thuê bao thành công", packageService.get(id));
    }
}
