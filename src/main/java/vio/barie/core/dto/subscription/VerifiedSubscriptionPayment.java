package vio.barie.core.dto.subscription;

import java.time.Instant;
import java.util.Map;

/**
 * Internal evidence from a verified gateway adapter; never accepted as a browser request.
 * A missing paidAt means the gateway supplied no usable, authenticated payment timestamp.
 * The settlement service must retain that receipt for review instead of guessing server time.
 */
public record VerifiedSubscriptionPayment(
        Long subscriptionId,
        Long paymentId,
        String source,
        String reference,
        String invoiceNo,
        Long amountVnd,
        String currency,
        Instant paidAt,
        Instant receivedAt,
        Map<String, Object> metadata) {
    public VerifiedSubscriptionPayment {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
