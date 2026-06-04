package vio.barie.core.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vio.barie.core.common.BarrierActionType;
import vio.barie.core.entity.base.BaseEntity;
import vio.barie.core.utils.DirectionType;

import java.time.LocalDateTime;

@Entity
@Table(name = "barriers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BarrierEntity extends BaseEntity {

    @Column(name = "barrier_id", unique = true, nullable = false, length = 100)
    private String barrierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_device_id", nullable = false)
    private ParkingDeviceEntity parkingDevice;

    @Column(name = "description", length = 255)
    private String description;


    @Enumerated(EnumType.STRING)
    private DirectionType direction;

    @Column(name = "modbus_addr")
    private Integer modbusAddr;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (getCreatedAt() == null) {
            setCreatedAt(now);
        }
        if (getUpdatedAt() == null) {
            setUpdatedAt(now);
        }
    }

    @PreUpdate
    public void preUpdate() {
        setUpdatedAt(LocalDateTime.now());
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private BarrierActionType action;
}
