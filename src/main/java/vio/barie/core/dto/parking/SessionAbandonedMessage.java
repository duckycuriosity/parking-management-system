package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SessionAbandonedMessage {

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

    @JsonProperty("amount_due")
    private Integer amountDue;

    @JsonProperty("amount_paid")
    private Integer amountPaid;

    @JsonProperty("timestamp")
    private String timestamp;
}
