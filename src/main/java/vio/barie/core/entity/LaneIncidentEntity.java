package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vio.barie.core.common.LaneIncidentStatus;
import vio.barie.core.common.LaneIncidentType;
import vio.barie.core.dto.parking.EventImageInfo;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "lane_incident")
@Data
@EqualsAndHashCode(callSuper = true)
public class LaneIncidentEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private LaneIncidentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LaneIncidentStatus status;

    @Column(name = "lane_id")
    private Long laneId;

    @Column(name = "barrier_id")
    private String barrierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ParkingSessionEntity session;

    @Column(name = "detected_plate")
    private String detectedPlate;

    @Column(name = "entry_profile_id")
    private Integer entryProfileId;

    @Column(name = "exit_profile_id")
    private Integer exitProfileId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exit_image_info", columnDefinition = "jsonb")
    private List<EventImageInfo> exitImageInfo;

    /** Detection gốc tại làn ra, dùng để chạy lại closeExit khi admin xác nhận. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detection_payload", columnDefinition = "jsonb")
    private PendingEventDetection detectionPayload;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;
}
