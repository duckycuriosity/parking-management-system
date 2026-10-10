package vio.barie.core.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;

/** Immutable calculation input/output; only lifecycle status and settlement timestamp change. */
@Entity
@Table(name = "fee_calculation")
@Data
public class FeeCalculationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "fee_policy_id") private Integer feePolicyId;
    @Column(name = "charge_context", nullable = false) private String chargeContext;
    @Column(name = "calculated_at", nullable = false) private Instant calculatedAt;
    @Column(name = "calculation_until", nullable = false) private Instant calculationUntil;
    @Column(name = "valid_until") private Instant validUntil;
    @Column(name = "engine_revision", nullable = false) private String engineRevision;
    @Column(name = "input_fingerprint", nullable = false) private String inputFingerprint;
    @Column(name = "subtotal_vnd", nullable = false) private long subtotalVnd;
    @Column(name = "rounding_adjustment_vnd", nullable = false) private long roundingAdjustmentVnd;
    @Column(name = "total_vnd", nullable = false) private long totalVnd;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fee_detail", columnDefinition = "jsonb", nullable = false) private JsonNode feeDetail;
    @Column(name = "status", nullable = false) private String status = "QUOTED";
    @Column(name = "settled_at") private Instant settledAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @Transient private Frozen frozen;
    @PostLoad @PostPersist @PostUpdate private void freeze() {
        frozen = new Frozen(sessionId, feePolicyId, chargeContext, calculatedAt, calculationUntil, validUntil,
                engineRevision, inputFingerprint, subtotalVnd, roundingAdjustmentVnd, totalVnd,
                feeDetail == null ? null : feeDetail.deepCopy(), createdAt, status, settledAt);
    }
    @PrePersist private void validateNew() {
        if (sessionId == null || sessionId <= 0 || calculatedAt == null || calculationUntil == null
                || engineRevision == null || engineRevision.isBlank() || inputFingerprint == null
                || inputFingerprint.length() != 64 || feeDetail == null || !feeDetail.isObject()
                || chargeContext == null || chargeContext.isBlank() || createdAt == null
                || subtotalVnd < 0 || roundingAdjustmentVnd < 0 || roundingAdjustmentVnd >= 1000
                || totalVnd < 0 || totalVnd % 1000 != 0
                || Math.addExact(subtotalVnd, roundingAdjustmentVnd) != totalVnd
                || !"QUOTED".equals(status) || settledAt != null) {
            throw new IllegalStateException("Invalid immutable fee calculation");
        }
    }
    @PreUpdate private void validateUpdate() {
        if (frozen == null || !Objects.equals(frozen.sessionId, sessionId) || !Objects.equals(frozen.feePolicyId, feePolicyId)
                || !Objects.equals(frozen.chargeContext, chargeContext) || !Objects.equals(frozen.calculatedAt, calculatedAt)
                || !Objects.equals(frozen.calculationUntil, calculationUntil) || !Objects.equals(frozen.validUntil, validUntil)
                || !Objects.equals(frozen.engineRevision, engineRevision) || !Objects.equals(frozen.inputFingerprint, inputFingerprint)
                || frozen.subtotalVnd != subtotalVnd || frozen.roundingAdjustmentVnd != roundingAdjustmentVnd
                || frozen.totalVnd != totalVnd || !Objects.equals(frozen.feeDetail, feeDetail)
                || !Objects.equals(frozen.createdAt, createdAt)) {
            throw new IllegalStateException("A persisted fee calculation snapshot cannot be edited");
        }
        if (!("QUOTED".equals(frozen.status) && ("QUOTED".equals(status) || "SUPERSEDED".equals(status) || "SETTLED".equals(status)))
                && !Objects.equals(frozen.status, status)) {
            throw new IllegalStateException("Fee calculation lifecycle cannot move backwards");
        }
        if (("SETTLED".equals(status) && settledAt == null)
                || (!"SETTLED".equals(status) && settledAt != null)
                || (frozen.settledAt != null && !Objects.equals(frozen.settledAt, settledAt))) {
            throw new IllegalStateException("Fee settlement timestamp is immutable");
        }
    }
    @PreRemove private void preventDelete() { throw new IllegalStateException("Fee calculation history cannot be deleted"); }
    private record Frozen(Long sessionId, Integer feePolicyId, String chargeContext, Instant calculatedAt,
            Instant calculationUntil, Instant validUntil, String engineRevision, String inputFingerprint,
            long subtotalVnd, long roundingAdjustmentVnd, long totalVnd, JsonNode feeDetail,
            Instant createdAt, String status, Instant settledAt) { }
}
