package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "vehicle_customer")
public class VehicleCustomerEntity extends BaseEntity {

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "relation_type", nullable = false, length = 30)
    private String relationType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
