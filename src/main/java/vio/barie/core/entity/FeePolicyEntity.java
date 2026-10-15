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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.utils.VehicleType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A published tariff version. Historical pricing and bands must remain unchanged. */
@Entity
@Table(name = "fee_policy", uniqueConstraints = {
        @UniqueConstraint(name = "uq_fee_policy_scope_version",
                columnNames = {"parking_lot_id", "vehicle_type", "version_no"})
}, indexes = {
        @Index(name = "idx_fee_policy_lot_vehicle_effective_from",
                columnList = "parking_lot_id, vehicle_type, effective_from")
})
@Getter
@Setter
public class FeePolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "parking_lot_id", nullable = false)
    private Long parkingLotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false, columnDefinition = "timestamptz")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to", columnDefinition = "timestamptz")
    private LocalDateTime effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_method", nullable = false, length = 30)
    private FeeCalculationMethod calculationMethod;

    @Column(name = "daily_price_vnd")
    private Long dailyPriceVnd;

    @Column(name = "remainder_threshold_minutes")
    private Integer remainderThresholdMinutes;

    // Reserved for a later rule contract. New versions currently store an empty object.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pricing_rules", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> pricingRules = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "feePolicy", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<FeeTimeBandEntity> timeBands = new ArrayList<>();

    public void addTimeBand(FeeTimeBandEntity timeBand) {
        timeBands.add(timeBand);
        timeBand.setFeePolicy(this);
    }
}
