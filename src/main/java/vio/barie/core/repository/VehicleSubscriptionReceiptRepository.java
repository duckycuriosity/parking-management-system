package vio.barie.core.repository;

import org.springframework.data.repository.Repository;
import vio.barie.core.entity.VehicleSubscriptionReceiptEntity;

import java.util.List;
import java.util.Optional;

/** Append-only evidence: deliberately does not expose delete or bulk-update APIs. */
public interface VehicleSubscriptionReceiptRepository
        extends Repository<VehicleSubscriptionReceiptEntity, Long> {

    // Spring Data delegates this signature to SimpleJpaRepository. Entity lifecycle
    // guards reject updates of persisted evidence, including EntityManager.merge.
    <S extends VehicleSubscriptionReceiptEntity> S saveAndFlush(S receipt);

    Optional<VehicleSubscriptionReceiptEntity> findById(Long id);

    Optional<VehicleSubscriptionReceiptEntity> findBySourceAndReference(String source, String reference);

    // More than one real settlement can target the same invoice; preserve every receipt.
    List<VehicleSubscriptionReceiptEntity> findByPaymentId(Long paymentId);

    List<VehicleSubscriptionReceiptEntity> findBySubscriptionIdOrderByReceivedAtAscIdAsc(Long subscriptionId);
}
