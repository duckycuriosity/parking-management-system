package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "owner_account")
public class OwnerAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Core AI identifier; never supplied by a profile save request. */
    @Column(name = "ai_profile_id")
    private Long aiProfileId;

    @Column(name = "ai_identity_id", length = 100)
    private String aiIdentityId;

    /** Explicit same-person link, rather than matching names or phone numbers. */
    @Column(name = "driver_customer_id")
    private Long driverCustomerId;

    @Column(name = "ai_sync_state", nullable = false, length = 40)
    private String aiSyncState = "NOT_SYNCED";

    @Column(name = "ai_sync_token", length = 36)
    private String aiSyncToken;

    @Column(name = "ai_sync_started_at")
    private OffsetDateTime aiSyncStartedAt;

    /** Known provider ID retained when an old response cannot safely be attached. */
    @Column(name = "ai_sync_profile_id")
    private Long aiSyncProfileId;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "identity_number", length = 30)
    private String identityNumber;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "zalo_link", columnDefinition = "TEXT")
    private String zaloLink;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
