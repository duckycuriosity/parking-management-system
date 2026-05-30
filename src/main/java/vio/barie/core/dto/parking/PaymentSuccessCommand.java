package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentSuccessCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "payment_success";

    @JsonProperty("session_id")
    private Long sessionId;

    @JsonProperty("barrier_ids")
    private List<String> barrierIds;

    @JsonProperty("plate")
    private String plate;

    @JsonProperty("status")
    @Builder.Default
    private String status = "success";

    @JsonProperty("auto_idle_seconds")
    private int autoIdleSeconds;        // 0 → thiết bị dùng giá trị mặc định từ config

    @JsonProperty("timestamp")
    private String timestamp;
}
