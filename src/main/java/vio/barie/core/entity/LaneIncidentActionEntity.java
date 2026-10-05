package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.common.LaneIncidentActionType;
import vio.barie.core.entity.base.BaseEntity;

@Entity
@Table(name = "lane_incident_action")
@Data
@EqualsAndHashCode(callSuper = true)
public class LaneIncidentActionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private LaneIncidentEntity incident;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private LaneIncidentActionType actionType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username")
    private String actorUsername;

    @Column(name = "note")
    private String note;
}
