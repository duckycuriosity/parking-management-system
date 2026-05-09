package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenCmdCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "open_cmd";

    @JsonProperty("barrier_id")
    private String barrierId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("reason")
    private String reason;              // "payment_confirmed" | "monthly_ticket" | "manual_override" | "free_exit"

    @JsonProperty("play_sound")
    private boolean playSound;

    @JsonProperty("sound_file")
    private String soundFile;

    @JsonProperty("auto_close_seconds")
    private int autoCloseSeconds;
}
