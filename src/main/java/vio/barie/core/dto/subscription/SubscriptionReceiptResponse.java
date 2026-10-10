package vio.barie.core.dto.subscription;

import vio.barie.core.common.SubscriptionReceiptOutcome;

import java.time.Instant;

/** Financial evidence for one physical transfer; provider metadata is never exposed. */
public record SubscriptionReceiptResponse(
        Long id,
        Long subscriptionId,
        Long paymentId,
        String source,
        String reference,
        String invoiceNo,
        String currency,
        Long amountVnd,
        Instant paidAt,
        Instant receivedAt,
        SubscriptionReceiptOutcome outcome,
        String reasonCode,
        Instant createdAt) {
}
