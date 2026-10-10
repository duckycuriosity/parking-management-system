package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;
import vio.barie.core.entity.converter.AiProfileIdConverter;
import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "parking_customer")
public class ParkingCustomerEntity extends BaseEntity {

    @Column(name = "ai_identity_id", length = 100)
    private String aiIdentityId;

    @Convert(converter = AiProfileIdConverter.class)
    @Column(name = "ai_profile_id", columnDefinition = "bigint")
    private Integer aiProfileId;

    @Column(name = "ai_sync_state", nullable = false, length = 40)
    private String aiSyncState = "NOT_SYNCED";

    @Column(name = "ai_sync_token", length = 36)
    private String aiSyncToken;

    @Column(name = "ai_sync_started_at")
    private OffsetDateTime aiSyncStartedAt;

    @Column(name = "ai_sync_profile_id")
    private Long aiSyncProfileId;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "zalo_id", length = 100)
    private String zaloId;

    @Column(length = 255)
    private String username;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "email")
    private String email;
}
