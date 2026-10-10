package vio.barie.core.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;

/** One fee request/attempt reuses a shared payment intent; observations preserve extra transfers. */
@Entity
@Table(name = "parking_fee_payment", uniqueConstraints = {
        @UniqueConstraint(name = "uq_parking_fee_payment_request_key", columnNames = "request_key"),
        @UniqueConstraint(name = "uq_parking_fee_payment_payment", columnNames = "payment_id")})
@Data
public class ParkingFeePaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "calculation_id", nullable = false) private Long calculationId;
    @Column(name = "payment_id") private Long paymentId;
    @Column(name = "request_key", nullable = false) private String requestKey;
    @Column(name = "amount_vnd", nullable = false) private long amountVnd;
    @Column(name = "currency", nullable = false) private String currency = "VND";
    @Column(name = "status", nullable = false) private String status = "PENDING";
    @Column(name = "application_status", nullable = false) private String applicationStatus = "PENDING";
    @Column(name = "application_reason") private String applicationReason;
    @Column(name = "payment_source") private String paymentSource;
    @Column(name = "payment_reference") private String paymentReference;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "receipt", columnDefinition = "jsonb") private JsonNode receipt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Transient private Frozen frozen;
    @PostLoad @PostPersist @PostUpdate private void freeze() {
        frozen = new Frozen(calculationId, paymentId, requestKey, amountVnd, currency, requestedAt, createdAt,
                status, applicationStatus, paymentSource, paymentReference, confirmedAt,
                receipt == null ? null : receipt.deepCopy());
    }
    @PreUpdate private void validateUpdate() {
        if (frozen == null || !Objects.equals(frozen.calculationId, calculationId)
                || (frozen.paymentId != null && !Objects.equals(frozen.paymentId, paymentId))
                || !Objects.equals(frozen.requestKey, requestKey) || frozen.amountVnd != amountVnd
                || !Objects.equals(frozen.currency, currency) || !Objects.equals(frozen.requestedAt, requestedAt)
                || !Objects.equals(frozen.createdAt, createdAt)
                || (frozen.confirmedAt != null && !Objects.equals(frozen.confirmedAt, confirmedAt))
                || (frozen.paymentSource != null && !Objects.equals(frozen.paymentSource, paymentSource))
                || (frozen.paymentReference != null && !Objects.equals(frozen.paymentReference, paymentReference))
                || ("PAID".equals(frozen.status) && !"PAID".equals(status))
                || ("APPLIED".equals(frozen.applicationStatus) && !"APPLIED".equals(applicationStatus))) {
            throw new IllegalStateException("A fee attempt cannot change its binding or completed payment evidence");
        }
        if (frozen.receipt != null) {
            if (!frozen.receipt.isArray() || receipt == null || !receipt.isArray() || receipt.size() < frozen.receipt.size()) {
                throw new IllegalStateException("Fee receipt observations are append-only");
            }
            for (int index = 0; index < frozen.receipt.size(); index++) {
                if (!Objects.equals(frozen.receipt.get(index), receipt.get(index))) {
                    throw new IllegalStateException("Fee receipt observations are append-only");
                }
            }
        }
    }
    @PreRemove private void preventDelete() { throw new IllegalStateException("Fee payment history cannot be deleted"); }
    private record Frozen(Long calculationId, Long paymentId, String requestKey, long amountVnd, String currency,
            Instant requestedAt, Instant createdAt, String status, String applicationStatus,
            String paymentSource, String paymentReference, Instant confirmedAt, JsonNode receipt) { }
}
