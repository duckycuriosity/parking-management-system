package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import vio.barie.core.common.SubscriptionRegistrationType;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;

import java.time.Instant;
import java.util.Objects;

/** A quoted subscription period; payment state is separate from current time coverage. */
@Entity
@Table(name = "vehicle_subscription", uniqueConstraints = {
        @UniqueConstraint(name = "uq_vehicle_subscription_request_key", columnNames = "request_key"),
        @UniqueConstraint(name = "uq_vehicle_subscription_paid_receipt", columnNames = "paid_receipt_id")
}, indexes = {
        @Index(name = "idx_vehicle_subscription_vehicle_period",
                columnList = "vehicle_id, parking_lot_id, starts_at")
})
@Getter
@Setter
public class VehicleSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_key", nullable = false, length = 100)
    private String requestKey;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "parking_lot_id", nullable = false)
    private Long parkingLotId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_id", nullable = false)
    private SubscriptionPackageEntity subscriptionPackage;

    @Column(name = "previous_subscription_id")
    private Long previousSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_type", nullable = false, length = 20)
    private SubscriptionRegistrationType registrationType;

    @Column(name = "price_vnd", nullable = false)
    private Long priceVnd;

    @Column(name = "starts_at", nullable = false, columnDefinition = "timestamptz")
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, columnDefinition = "timestamptz")
    private Instant endsAt;

    @Column(name = "payment_deadline_at", nullable = false, columnDefinition = "timestamptz")
    private Instant paymentDeadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private VehicleSubscriptionStatus status = VehicleSubscriptionStatus.PENDING_PAYMENT;

    @Column(name = "paid_at", columnDefinition = "timestamptz")
    private Instant paidAt;

    @Column(name = "paid_receipt_id")
    private Long paidReceiptId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Transient
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private FrozenQuote persistedQuote;

    @Transient
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private PaymentFacts persistedPayment;

    /** No database reads here: cross-row contracts belong to the locked service. */
    @PrePersist
    void validateNewOrder() {
        validateShape();
        require(status == VehicleSubscriptionStatus.PENDING_PAYMENT,
                "A new subscription must await verified payment");
    }

    @PostLoad
    @PostPersist
    @PostUpdate
    void rememberPersistedFacts() {
        persistedQuote = frozenQuote();
        persistedPayment = paymentFacts();
    }

    @PreUpdate
    void validateOrderUpdate() {
        validateShape();
        if (persistedQuote == null || !Objects.equals(persistedQuote, frozenQuote())) {
            throw immutable("Subscription quoted terms cannot be changed");
        }
        if (persistedPayment != null && persistedPayment.status() == VehicleSubscriptionStatus.PAID
                && !Objects.equals(persistedPayment, paymentFacts())) {
            throw immutable("A paid subscription period cannot be changed");
        }
        if (persistedPayment != null
                && (persistedPayment.status() == VehicleSubscriptionStatus.CANCELLED
                    || persistedPayment.status() == VehicleSubscriptionStatus.PAYMENT_EXCEPTION)
                && persistedPayment.status() != status) {
            throw immutable("Reopening a cancelled or reviewed order requires an approved workflow");
        }
    }

    @PreRemove
    void preventPaidOrderDeletion() {
        if (status == VehicleSubscriptionStatus.PAID
                || (persistedPayment != null && persistedPayment.status() == VehicleSubscriptionStatus.PAID)) {
            throw immutable("A paid subscription period cannot be deleted");
        }
    }

    private void validateShape() {
        require(requestKey != null && !requestKey.isBlank() && requestKey.length() <= 100,
                "requestKey must contain 1 to 100 characters");
        require(vehicleId != null && vehicleId > 0 && parkingLotId != null && parkingLotId > 0,
                "Vehicle and parking lot identifiers must be positive");
        require(getPackageId() != null && getPackageId() > 0, "A persisted package version is required");
        require(priceVnd != null && priceVnd >= 0, "Subscription price must be nonnegative");
        require(startsAt != null && endsAt != null && startsAt.isBefore(endsAt),
                "Subscription period requires startsAt < endsAt");
        require(paymentDeadlineAt != null, "Payment deadline is required");
        require(microsecond(startsAt) && microsecond(endsAt) && microsecond(paymentDeadlineAt)
                        && microsecond(paidAt), "Subscription timestamps require microsecond precision");
        require(status != null && registrationType != null, "Subscription status and registration type are required");
        require((registrationType == SubscriptionRegistrationType.NEW && previousSubscriptionId == null)
                        || (registrationType == SubscriptionRegistrationType.RENEW && previousSubscriptionId != null
                            && previousSubscriptionId > 0 && !Objects.equals(previousSubscriptionId, id)),
                "Registration type does not match the previous subscription");
        if (status == VehicleSubscriptionStatus.PAID) {
            require(paidAt != null && paidReceiptId != null && paidReceiptId > 0
                            && !paidAt.isAfter(paymentDeadlineAt),
                    "A paid subscription requires its receipt and payment time within the deadline");
        } else {
            require(paidAt == null && paidReceiptId == null, "An unpaid subscription cannot carry applied payment facts");
        }
    }

    private FrozenQuote frozenQuote() {
        return new FrozenQuote(id, requestKey, vehicleId, parkingLotId, getPackageId(), previousSubscriptionId,
                registrationType, priceVnd, startsAt, endsAt, paymentDeadlineAt, createdAt);
    }

    private PaymentFacts paymentFacts() { return new PaymentFacts(status, paidAt, paidReceiptId); }
    private static boolean microsecond(Instant value) { return value == null || value.getNano() % 1000 == 0; }
    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException(message, "SUBSCRIPTION_INVALID", 400);
    }
    private static BusinessException immutable(String message) {
        return new BusinessException(message, "SUBSCRIPTION_IMMUTABLE", 409);
    }
    private record FrozenQuote(Long id, String requestKey, Long vehicleId, Long parkingLotId, Long packageId,
                               Long previousSubscriptionId, SubscriptionRegistrationType registrationType,
                               Long priceVnd, Instant startsAt, Instant endsAt, Instant deadlineAt, Instant createdAt) { }
    private record PaymentFacts(VehicleSubscriptionStatus status, Instant paidAt, Long receiptId) { }

    public Long getPackageId() {
        return subscriptionPackage == null ? null : subscriptionPackage.getId();
    }
}
