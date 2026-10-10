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
import vio.barie.core.dto.subscription.RegisterSubscriptionRequest;
import vio.barie.core.dto.subscription.RenewSubscriptionRequest;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.dto.subscription.SubscriptionReceiptResponse;
import vio.barie.core.service.VehicleSubscriptionService;
import vio.barie.core.service.VehicleSubscriptionListingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/vehicle-subscriptions")
@RequiredArgsConstructor
@Tag(name = "Vehicle Subscription", description = "Đăng ký, gia hạn và tra cứu kỳ thuê bao của phương tiện")
public class VehicleSubscriptionController {
    private final VehicleSubscriptionService service;
    private final VehicleSubscriptionListingService listingService;

    @GetMapping
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Tra cứu danh sách thuê bao, phân trang trước khi đọc nhãn hiển thị")
    public Result<Page<vio.barie.core.dto.VehicleSubscriptionResponse>> findAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long parkingLotId,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 0 || size < 1 || size > 200)
            throw new vio.barie.core.config.exception.BusinessException("Require page >= 0 and size between 1 and 200", "SUBSCRIPTION_INVALID", 400);
        return Result.ok("Đã lấy thông tin đăng ký xe thành công", listingService.findAll(keyword, buildingId,
                parkingLotId, startDate, endDate, PageRequest.of(page, size)));
    }

    @PostMapping("/register")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @Operation(summary = "Đăng ký kỳ thuê bao mới và chờ thanh toán")
    public Result<SubscriptionResponse> register(@Valid @RequestBody RegisterSubscriptionRequest request) {
        return Result.ok("Đăng ký thuê bao thành công", service.register(request));
    }

    @PostMapping("/renew")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @Operation(summary = "Tạo kỳ gia hạn nối tiếp kỳ đã thanh toán trước đó")
    public Result<SubscriptionResponse> renew(@Valid @RequestBody RenewSubscriptionRequest request) {
        return Result.ok("Tạo kỳ gia hạn thành công", service.renew(request));
    }

    @GetMapping("/current")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Lấy các kỳ đã thanh toán đang nằm trong thời hạn hiện tại")
    public Result<List<SubscriptionResponse>> current(
            @RequestParam @Positive Long vehicleId,
            @RequestParam @Positive Long parkingLotId) {
        return Result.ok("Lấy kỳ thuê bao hiện tại thành công", service.current(vehicleId, parkingLotId));
    }

    @GetMapping("/history")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Lấy lịch sử đăng ký và thanh toán thuê bao của xe tại bãi")
    public Result<List<SubscriptionResponse>> history(
            @RequestParam @Positive Long vehicleId,
            @RequestParam @Positive Long parkingLotId) {
        return Result.ok("Lấy lịch sử thuê bao thành công", service.history(vehicleId, parkingLotId));
    }

    @GetMapping("/paid")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Lấy các kỳ đã thanh toán giao với khoảng thời gian chỉ định")
    public Result<List<SubscriptionResponse>> paid(
            @RequestParam @Positive Long vehicleId,
            @RequestParam @Positive Long parkingLotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
        return Result.ok("Lấy các kỳ đã thanh toán thành công",
                service.findPaidOverlapping(vehicleId, parkingLotId, from, until));
    }

    @GetMapping("/{id}/receipts")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Xem các khoản tiền đã xác minh và lý do cần đối soát của thuê bao")
    public Result<List<SubscriptionReceiptResponse>> paymentReceipts(@PathVariable @Positive Long id) {
        return Result.ok("Lấy lịch sử thanh toán thuê bao thành công", service.paymentReceipts(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @Operation(summary = "Lấy chi tiết một kỳ thuê bao và quyền lợi đã chốt")
    public Result<SubscriptionResponse> getById(@PathVariable @Positive Long id) {
        return Result.ok("Lấy chi tiết thuê bao thành công", service.getById(id));
    }
}
