package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SupportRequestMessage {

    @JsonProperty("type")
    private String type;

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("barrier_id")
    private String barrierId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("plate")
    private String plate;

    @JsonProperty("reason")
    private String reason;          // "payment_timeout" | "user_request" | "idle_press"

    @JsonProperty("timestamp")
    private String timestamp;
}
