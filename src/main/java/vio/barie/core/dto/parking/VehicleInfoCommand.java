package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VehicleInfoCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "vehicle_info";

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("scenario_tag")
    private String scenarioTag;

    @JsonProperty("barrier_id")
    private String barrierId;

    @JsonProperty("direction")
    private String direction;           // "entry" | "exit"

    @JsonProperty("plate")
    private String plate;

    @JsonProperty("ticket_type")
    private String ticketType;          // "hourly" | "monthly"

    // ── Vé lượt (hourly) ─────────────────────────────────────────
    @JsonProperty("entry_time")
    private String entryTime;

    @JsonProperty("exit_time")
    private String exitTime;

    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    @JsonProperty("amount_due")
    private Integer amountDue;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("payment_methods")
    private List<String> paymentMethods;

    @JsonProperty("qr_url")
    private String qrUrl;

    @JsonProperty("qr_expires_at")
    private String qrExpiresAt;

    // ── Vé tháng (monthly) ───────────────────────────────────────
    @JsonProperty("open")
    private Boolean open;

    @JsonProperty("owner_name")
    private String ownerName;

    @JsonProperty("valid_until")
    private String validUntil;
}
