package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_device_permission",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_user_device",
                columnNames = {"parking_customer_id", "device_id"}
        )
)
public class UserDevicePermissionEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_customer_id", nullable = false)
    private ParkingCustomerEntity parkingCustomer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private DeviceEntity device;

    @Column(name = "role")
    private String role; // OWNER , SHARED

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;
}