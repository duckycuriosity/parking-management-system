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
    private String aiCameraId;

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

    @Column(name = "roi_x1")
    private Double roiX1;

    @Column(name = "roi_y1")
    private Double roiY1;

    @Column(name = "roi_x2")
    private Double roiX2;

    @Column(name = "roi_y2")
    private Double roiY2;

    @Column(name = "roi_x3")
    private Double roiX3;

    @Column(name = "roi_y3")
    private Double roiY3;

    @Column(name = "roi_x4")
    private Double roiX4;

    @Column(name = "roi_y4")
    private Double roiY4;

    @ManyToOne(fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JoinColumn(name = "lane_id", nullable = true)
    private LaneEntity lane;

    @Column(name = "mode")
    private String mode;

    @Column(name = "protocol")
    private String protocol;

}
