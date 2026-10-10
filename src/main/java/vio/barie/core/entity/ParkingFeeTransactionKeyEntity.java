package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/** Global transaction identity registry only; financial evidence stays on the fee attempt. */
@Entity @Table(name = "parking_fee_transaction_key")
@IdClass(ParkingFeeTransactionKeyId.class) @Data
public class ParkingFeeTransactionKeyEntity {
    @Id @Column(name = "source", length = 50) private String source;
    @Id @Column(name = "reference", length = 255) private String reference;
    @Column(name = "attempt_id", nullable = false) private Long attemptId;
    @Column(name = "identity_fingerprint", length = 64, nullable = false) private String identityFingerprint;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @PreUpdate private void preventUpdate() { throw new IllegalStateException("Verified transaction identities cannot be reassigned"); }
    @PreRemove private void preventDelete() { throw new IllegalStateException("Verified transaction identities cannot be deleted"); }
}
