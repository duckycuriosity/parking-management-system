package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import vio.barie.core.utils.DirectionType;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LaneEventCommand {
    public static final String VEHICLE_ARRIVING = "VEHICLE_ARRIVING";
    public static final String PLATE_RECOGNIZED = "PLATE_RECOGNIZED";
    public static final String ENTRY_SUCCESS = "ENTRY_SUCCESS";
    public static final String EXIT_SUCCESS = "EXIT_SUCCESS";
    public static final String FACE_MISMATCH_CONFIRM = "FACE_MISMATCH_CONFIRM";
    public static final String PLATE_CONFIRM_REQUIRED = "PLATE_CONFIRM_REQUIRED";

    @JsonProperty("type")
    @Builder.Default
    private String type = "lane_event";

    @JsonProperty("event") private String event;

    @JsonProperty("lane_id") private Long laneId;
    @JsonProperty("barrier_id") private String barrierId;
    @JsonProperty("direction") private DirectionType directionType;

    @JsonProperty("plate") private String plate;
    @JsonProperty("vehicle_type") private String vehicleType;
    @JsonProperty("confidence") private Double confidence;
    @JsonProperty("profile_id") private Integer profileId;
    @JsonProperty("session_id") private Long sessionId;
    @JsonProperty("incident_id") private Long incidentId;

    @JsonProperty("images") private List<LaneEventImage> images;

    @JsonProperty("event_ts") private Long eventTs;
    @JsonProperty("server_ts") private Long serverTs;
}
