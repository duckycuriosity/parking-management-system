package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "parking_customer")
public class ParkingCustomerEntity extends BaseEntity {

    @Column(name = "ai_identity_id", nullable = false, length = 100)
    private String aiIdentityId;

    @Column(name = "zalo_id", length = 100)
    private String zaloId;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;
}