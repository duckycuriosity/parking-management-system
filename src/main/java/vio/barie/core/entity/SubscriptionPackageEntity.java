package vio.barie.core.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Transient;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.utils.VehicleType;
import vio.barie.core.config.exception.BusinessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One catalog version. Published commercial terms and schedules are immutable. */
@Entity
@Table(name = "subscription_package", uniqueConstraints = {
        @UniqueConstraint(name = "uq_subscription_package_code_version",
                columnNames = {"package_code", "version_no"})
}, indexes = {
        @Index(name = "idx_subscription_package_catalog",
                columnList = "parking_lot_id, vehicle_type, effective_from")
})
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

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "price_vnd", nullable = false)
    private Long priceVnd;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_mode", nullable = false, length = 20)
    private SubscriptionCoverageMode coverageMode;

    @Column(name = "effective_from", nullable = false, columnDefinition = "timestamptz")
    private Instant effectiveFrom;

    @Column(name = "effective_to", columnDefinition = "timestamptz")
    private Instant effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionPackageStatus status = SubscriptionPackageStatus.DRAFT;

    @Column(name = "sale_enabled", nullable = false)
    private boolean saleEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @OneToMany(mappedBy = "subscriptionPackage", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SubscriptionPackageScheduleEntity> schedules = new ArrayList<>();

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Contract loadedContract;

    @PostLoad
    @PostPersist
    @PostUpdate
    private void rememberContract() {
        loadedContract = new Contract(packageCode, versionNo, parkingLotId, vehicleType,
                name, priceVnd, durationDays, coverageMode, effectiveFrom, status, createdAt);
    }

    @PreUpdate
    private void protectContract() {
        if (loadedContract == null) return;
        Contract current = new Contract(packageCode, versionNo, parkingLotId, vehicleType,
                name, priceVnd, durationDays, coverageMode, effectiveFrom, status, createdAt);
        if (!Objects.equals(loadedContract.code, packageCode) || !Objects.equals(loadedContract.version, versionNo)
                || !Objects.equals(loadedContract.lot, parkingLotId) || loadedContract.type != vehicleType) {
            throw new BusinessException("Package code, version, lot and vehicle type are immutable", "CONFLICT", 409);
        }
        if (loadedContract.status == SubscriptionPackageStatus.PUBLISHED && !loadedContract.equals(current)) {
            throw new BusinessException("Published package terms are immutable; create a new version", "CONFLICT", 409);
        }
    }

    @PreRemove
    private void protectPublishedVersion() {
        if (status == SubscriptionPackageStatus.PUBLISHED) {
            throw new BusinessException("A published package cannot be deleted", "CONFLICT", 409);
        }
    }

    private record Contract(String code, Integer version, Long lot, VehicleType type, String name,
                            Long price, Integer days, SubscriptionCoverageMode coverage, Instant from,
                            SubscriptionPackageStatus status, Instant created) { }

    public void addSchedule(SubscriptionPackageScheduleEntity schedule) {
        schedules.add(schedule);
        schedule.setSubscriptionPackage(this);
    }
}
