package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "camera")
@Data
@EqualsAndHashCode(callSuper = true)
public class CameraEntity extends BaseEntity {

    @Column(name = "code")
    private String code;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "status")
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lane_id", nullable = false)
    private LaneEntity lane;
}
