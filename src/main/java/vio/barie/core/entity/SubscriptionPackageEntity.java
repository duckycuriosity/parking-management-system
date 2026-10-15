package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscription_package")
@Getter
@Setter
public class SubscriptionPackageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "package_code", nullable = false, length = 100)
    private String packageCode;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "parking_lot_id", nullable = false)
    private Long parkingLotId;

    @Column(name = "vehicle_type", nullable = false, length = 20)
    private String vehicleType;

    @Column(name = "price_vnd", nullable = false, precision = 18, scale = 2)
    private BigDecimal priceVnd;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "coverage_mode", nullable = false, length = 20)
    private String coverageMode;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "sale_enabled", nullable = false)
    private boolean saleEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
