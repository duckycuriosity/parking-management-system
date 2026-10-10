package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The graph and its replay identity commit together. Only internal IDs are recorded. */
@Entity @Table(name = "owner_profile_registration") @Getter @Setter
public class OwnerProfileRegistrationEntity {
    @Id @Column(name = "request_key", length = 100, updatable = false)
    private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;
    @Column(name = "owner_id") private Long ownerId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "result_ids", columnDefinition = "jsonb")
    private String resultIds;
    // Nullable version makes assigned-ID save persist, never overwrite by merge.
    @Version @Column(name = "row_version", nullable = false) private Long rowVersion;
}
