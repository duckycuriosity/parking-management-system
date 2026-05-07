package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(
        name = "user_device_permission",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_user_device",
                columnNames = {"parking_customer_id", "device_id"}
        )
)
@Data
@EqualsAndHashCode(callSuper = true)
public class VehicleUserPermissionEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_customer_id", nullable = false)
    private ParkingCustomerEntity parkingCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private VehicleEntity vehicle;

    @Column(name = "role")
    private String role; // OWNER , SHARED
}