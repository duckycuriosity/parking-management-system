package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.config.exception.BusinessException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only audit of verified settlements, including payments needing review. */
@Entity
@Table(name = "vehicle_subscription_receipt", uniqueConstraints = {
        @UniqueConstraint(name = "uq_subscription_receipt_source_reference",
                columnNames = {"source", "reference"})
})
@Getter
@Setter
public class VehicleSubscriptionReceiptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "reference", nullable = false, length = 100)
    private String reference;

    @Column(name = "invoice_no", nullable = false, length = 100)
    private String invoiceNo;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "paid_at", columnDefinition = "timestamptz")
    private Instant paidAt;

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private SubscriptionReceiptOutcome outcome;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;
    /** Each receipt is inserted once. Verified facts and outcome are never rewritten. */
    @PrePersist
    void validateNewReceipt() {
        require(subscriptionId != null && subscriptionId > 0 && paymentId != null && paymentId > 0,
                "Receipt must reference a subscription and payment request");
        require(nonblank(source, 50) && nonblank(reference, 100) && nonblank(invoiceNo, 100)
                        && nonblank(currency, 10), "Receipt payment identifiers are required");
        require(amountVnd != null && amountVnd >= 0, "Actual receipt amount must be nonnegative");
        require(receivedAt != null && microsecond(receivedAt) && microsecond(paidAt),
                "Receipt observation time is required with microsecond timestamp precision");
        require(metadata != null, "Receipt metadata must be a JSON object");
        require(outcome != null, "Receipt outcome is required");
        if (outcome == SubscriptionReceiptOutcome.APPLIED) {
            require(paidAt != null && !paidAt.isAfter(receivedAt) && "VND".equals(currency) && reasonCode == null,
                    "An applied receipt requires a valid VND payment time without a review reason");
        } else {
            require(nonblank(reasonCode, 100), "A review receipt requires a reason");
        }
    }

    @PreUpdate
    void preventReceiptUpdate() { throw immutable(); }

    @PreRemove
    void preventReceiptDeletion() { throw immutable(); }

    private static boolean nonblank(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }
    private static boolean microsecond(Instant value) { return value == null || value.getNano() % 1000 == 0; }
    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException(message, "SUBSCRIPTION_INVALID", 400);
    }
    private static BusinessException immutable() {
        return new BusinessException("Subscription receipts are append-only audit records", "SUBSCRIPTION_IMMUTABLE", 409);
    }
}
