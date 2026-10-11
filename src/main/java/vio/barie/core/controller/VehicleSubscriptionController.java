package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.VehicleSubscriptionResponse;
import vio.barie.core.service.VehicleSubscriptionService;

import java.time.LocalDateTime;
@Slf4j
@RestController
@RequestMapping("/api/vehicle-subscriptions")
@RequiredArgsConstructor
public class VehicleSubscriptionController {
    private final VehicleSubscriptionService vehicleSubscriptionService;

    @GetMapping
    public Result<Page<VehicleSubscriptionResponse>> findAll(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long parkingLotId,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {

        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Ngày kết thúc phải sau ngày bắt đầu.");
        }

        Pageable pageable = PageRequest.of(page, size);

        return Result.ok("Đã lấy thông tin đăng ký xe thành công",
                vehicleSubscriptionService.findAll(keyword, buildingId, parkingLotId, startDate, endDate, pageable));
    }
}
