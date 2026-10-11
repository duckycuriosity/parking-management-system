package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.service.VehicleSubscriptionPaymentService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner-accounts/{ownerId}/subscriptions/{subscriptionId}")
public class VehicleSubscriptionPaymentController {
    private final VehicleSubscriptionPaymentService service;

    @PostMapping("/payment-link")
    public Result<CreatePaymentResponse> createPaymentLink(@PathVariable Long ownerId,
                                                           @PathVariable Long subscriptionId) {
        return Result.ok(service.createPaymentLink(ownerId, subscriptionId));
    }
}
