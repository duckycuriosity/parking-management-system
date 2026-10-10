package vio.barie.core.dto.fee;

import java.time.Instant;
import java.util.Map;

/** Internal adapter evidence only. Public clients cannot submit this record to mark a fee paid. */
public record VerifiedParkingPayment(Long paymentId, String source, String reference, String invoiceNo,
        Long amountVnd, String currency, Instant paidAt, Instant receivedAt, String status,
        Map<String, Object> metadata) {
    public VerifiedParkingPayment { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
}
