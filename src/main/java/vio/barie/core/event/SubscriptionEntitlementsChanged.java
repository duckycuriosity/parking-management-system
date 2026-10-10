package vio.barie.core.event;

import java.time.Instant;

/**
 * A paid period was added. The service publishes this event only after commit;
 * ordinary event listeners may invalidate their caches or enqueue transport work.
 * This notification is an invalidation hint, not evidence that a quote was recalculated;
 * settlement must still re-read rights while holding the same vehicle row lock.
 */
public record SubscriptionEntitlementsChanged(Long subscriptionId, Long vehicleId, Long parkingLotId,
                                              Instant startsAt, Instant endsAt, Long paidReceiptId) {
}
