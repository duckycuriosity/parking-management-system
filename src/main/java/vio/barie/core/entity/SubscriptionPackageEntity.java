package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Maps the existing V50 table; scalar IDs do not create database foreign keys. */
@Entity
@Table(name = "subscription_package")
@Getter
@Setter
public class SubscriptionPackageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "parking_lot_id") private Long parkingLotId;
    @Column(name = "vehicle_type") private String vehicleType;
    @Column(name = "name") private String name;
    @Column(name = "price_vnd", precision = 18, scale = 2) private BigDecimal priceVnd;
    @Column(name = "duration_days") private Integer durationDays;
    @Column(name = "effective_from") private OffsetDateTime effectiveFrom;
    @Column(name = "effective_to") private OffsetDateTime effectiveTo;
    @Column(name = "status") private String status;
    @Column(name = "sale_enabled") private boolean saleEnabled;
}
