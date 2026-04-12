package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "parking_customer")
public class ParkingCustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ai_identity_id", nullable = false, length = 100)
    private String aiIdentityId;

    @Column(name = "zalo_id", nullable = false, length = 100)
    private String zaloId;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;
}