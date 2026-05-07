package vio.barie.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "vms_event")
@Data
@EqualsAndHashCode(callSuper = true)
public class VmsEventEntity extends BaseEntity {

    @Column(name = "vms_event_id", nullable = false, unique = true)
    private Long vmsEventId;

    @Column(name = "service_id")
    private Integer serviceId;

    @Column(name = "origin_id")
    private Long originId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "is_confirmed")
    private Boolean isConfirmed;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "event_epoch_seconds", nullable = false)
    private Double eventEpochSeconds;

    @Column(name = "camera_uuid", length = 120)
    private String cameraUuid;

    @Column(name = "monitor_type", length = 120)
    private String monitorType;

    @Column(name = "profile_uuid", length = 120)
    private String profileUuid;

    @Column(name = "profile_name", length = 255)
    private String profileName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;
}