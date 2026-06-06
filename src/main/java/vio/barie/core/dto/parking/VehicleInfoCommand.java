package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VehicleInfoCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "vehicle_info";

    @JsonProperty("session_id")
    private String session_id;

    @JsonProperty("scenario_tag")
    private String scenarion_tag;

    @JsonProperty("barrier_id")
    private String barrier_id;

    @JsonProperty("direction")
    private String direction;           // "entry" | "exit"

    @JsonProperty("plate")
    private String plate;

    @JsonProperty("ticket_type")
    private String ticket_type;          // "hourly" | "monthly"

    // ── Vé lượt (hourly) ─────────────────────────────────────────
    @JsonProperty("entry_time")
    private String entry_time;

    @JsonProperty("exit_time")
    private String exit_time;

    @JsonProperty("duration_minutes")
    private Integer duration_minutes;

    @JsonProperty("amount_due")
    private BigDecimal amount_due;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("payment_methods")
    private List<String> payment_methods;

    @JsonProperty("qr_url")
    private String qr_url;

    @JsonProperty("qr_expires_at")
    private String qr_expires_at;

    // ── Vé tháng (monthly) ───────────────────────────────────────
    @JsonProperty("open")
    private Boolean open;

    @JsonProperty("owner_name")
    private String owner_name;

    @JsonProperty("valid_until")
    private String valid_until;
}
