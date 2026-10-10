package vio.barie.core.controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.service.VehicleSubscriptionPaymentService;

@RestController
@RequestMapping("/api/vehicle-subscriptions")
@RequiredArgsConstructor
public class VehicleSubscriptionPaymentController {
    private final VehicleSubscriptionPaymentService payments;

    @PostMapping("/{id}/payment-link")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public Result<CreatePaymentResponse> createPaymentLink(@PathVariable @Positive Long id) {
        return Result.ok(payments.createPaymentLink(id));
    }
}
