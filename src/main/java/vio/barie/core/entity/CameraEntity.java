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

    @Column(name = "camera_uuid")
    private String cameraUuid;

    @Column(name = "name")
    private String name;

    @Column(name = "url")
    private String url;

    @Column(name = "type")
    private String type;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lane_id")
    private LaneEntity lane;
}
