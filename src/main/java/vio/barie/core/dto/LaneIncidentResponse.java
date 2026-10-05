package vio.barie.core.dto;

import lombok.Builder;
import lombok.Data;
import vio.barie.core.common.LaneIncidentStatus;
import vio.barie.core.common.LaneIncidentType;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class LaneIncidentResponse {
    private Long id;
    private LaneIncidentType type;
    private LaneIncidentStatus status;

    private Long laneId;
    private String barrierId;
    private Long sessionId;

    private String plateNumber;
    private Integer entryProfileId;
    private Integer exitProfileId;

    /** role = entry_face | entry_plate | exit_face | exit_plate */
    private List<LaneEventImage> images;

    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
