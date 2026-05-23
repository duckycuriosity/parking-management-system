package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Entity
@Table(name = "parking_customer")
public class ParkingCustomerEntity extends BaseEntity {

    @Column(name = "ai_identity_id", nullable = false, length = 100)
    private String aiIdentityId;

    @Column(name = "ai_profile_id", nullable = false, length = 100)
    private int aiProfileId;

    @Column(name = "zalo_id", length = 100)
    private String zaloId;

    @Column(length = 255)
    private String username;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;
}