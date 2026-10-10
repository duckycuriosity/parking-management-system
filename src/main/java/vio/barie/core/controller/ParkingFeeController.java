package vio.barie.core.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.fee.*;
import vio.barie.core.service.ParkingFeeService;
import vio.barie.core.service.ParkingPaymentService;
import java.util.List;

/** No browser endpoint can declare a transfer PAID; only verified gateway adapters may do so. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/parking-fees")
public class ParkingFeeController {
    private final ParkingFeeService fees;
    private final ParkingPaymentService payments;

    @PostMapping("/simulate")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public Result<ParkingFeeResponse> simulate(@Valid @RequestBody ParkingFeeQueryRequest request) {
        return Result.ok("Tính thử phí gửi xe", fees.simulate(request));
    }

    @PostMapping("/quotes")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public Result<ParkingFeeResponse> quote(@Valid @RequestBody ParkingFeeQuoteRequest request) {
        return Result.ok("Lưu báo giá và căn cứ tính phí", fees.quote(request));
    }

    @GetMapping("/calculations/{id}")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public Result<ParkingFeeResponse> get(@PathVariable @Positive Long id) {
        return Result.ok(fees.getCalculation(id));
    }

    @GetMapping("/sessions/{id}/calculations")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public Result<List<ParkingFeeResponse>> history(@PathVariable @Positive Long id) {
        return Result.ok(fees.history(id));
    }

    @PostMapping("/calculations/{id}/payment")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public Result<ParkingPaymentResponse> requestPayment(@PathVariable @Positive Long id,
            @Valid @RequestBody ParkingFeePaymentRequest request) {
        return Result.ok("Tạo yêu cầu thu theo bản tính đã chấp nhận",
                payments.createPaymentRequest(id, request.requestKey()));
    }

    @GetMapping("/calculations/{id}/payments")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public Result<List<ParkingFeePaymentEvidenceResponse>> paymentHistory(@PathVariable @Positive Long id) {
        return Result.ok(payments.getAttempts(id));
    }

    @PostMapping("/calculations/{id}/settle-no-charge")
    @PreAuthorize("!@feePolicySecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public Result<ParkingPaymentResponse> noCharge(@PathVariable @Positive Long id) {
        return Result.ok("Chốt bản tính không phát sinh khoản thu", payments.settleNoCharge(id));
    }
}
