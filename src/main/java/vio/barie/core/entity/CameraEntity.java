package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "camera")
@Data
@EqualsAndHashCode(callSuper = true)
public class CameraEntity extends BaseEntity {

    @Column(name = "ai_camera_id", unique = true)
    private Long aiCameraId;

    @Column(name = "name")
    private String name;

    @Column(name = "uuid", unique = true)
    private String uuid;

    @Column(name = "url")
    private String url;

    @Column(name = "sub_url")
    private String subUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private BaseStatus type;

    @Column(name = "code")
    private String code;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private BaseStatus status;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @ManyToOne(fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JoinColumn(name = "lane_id", nullable = true)
    private LaneEntity lane;
}
