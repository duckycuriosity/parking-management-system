package vio.barie.core.event;

import java.time.Instant;

/** Emitted after the atomic DB settlement commits. Delivery to devices remains a separate concern. */
public record ParkingFeeSettled(Long sessionId, Long calculationId, Long paymentId, long amountVnd,
        Instant settledAt) { }
