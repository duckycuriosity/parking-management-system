package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PaymentMessage {

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

    @JsonProperty("method")
    private String method;          // "cash" | "qr"

    @JsonProperty("amount_due")
    private Integer amountDue;

    @JsonProperty("amount_paid")
    private Integer amountPaid;

    @JsonProperty("amount_change")
    private Integer amountChange;

    @JsonProperty("timestamp")
    private String timestamp;
}
