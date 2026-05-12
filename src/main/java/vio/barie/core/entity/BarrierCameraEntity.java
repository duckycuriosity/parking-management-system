package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "barrier_camera")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BarrierCameraEntity extends BaseEntity {
    @Column(name = "barrier_id", nullable = false, length = 100)
    private String barrierId;

    @Column(name = "camera_id", nullable = false)
    private Long cameraId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "priority")
    private Integer priority;
}
