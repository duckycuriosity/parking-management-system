package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "vehicle_subscription")
public class VehicleSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "parking_lot_id", nullable = false)
    private Long parkingLotId;

    @Column(name = "package_id", nullable = false)
    private Long packageId;

    @Column(name = "registration_type", nullable = false, length = 20)
    private String registrationType;

    @Column(name = "previous_subscription_id")
    private Long previousSubscriptionId;

    @Column(name = "price_vnd", nullable = false, precision = 18, scale = 2)
    private BigDecimal priceVnd;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "payment_deadline_at")
    private OffsetDateTime paymentDeadlineAt;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "payment_source", length = 50)
    private String paymentSource;

    @Column(name = "payment_reference")
    private String paymentReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payment_receipt", columnDefinition = "jsonb")
    private String paymentReceipt;

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
