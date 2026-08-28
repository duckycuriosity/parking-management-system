package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import vio.barie.core.utils.DirectionType;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LaneVehicleCommand {
  @JsonProperty("type")
  @Builder.Default
  private String type = "lane_vehicle";

  @JsonProperty("lane_id") private Long laneId;
  @JsonProperty("barrier_id") private String barrierId;
  @JsonProperty("direction") private DirectionType directionType;

  @JsonProperty("state") private String state;

  @JsonProperty("plate") private String plate;
  @JsonProperty("ai_plate_id") private String aiPlateId;
  @JsonProperty("confidence") private Double confidence;
  @JsonProperty("is_verified") private Boolean isVerified;
  @JsonProperty("vehicle_type") private String vehicleType;

  @JsonProperty("old_plate") private String previousPlate;

  @JsonProperty("event_ts") private Long eventTs;
  @JsonProperty("server_ts") private Long serverTs;
  @JsonProperty("session_ttl_seconds") private Long sessionTtlSeconds;

  @JsonProperty("images") private String image;
}
