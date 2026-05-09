package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DisplayMsgCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "display_msg";

    @JsonProperty("barrier_id")
    private String barrierId;

    @JsonProperty("message")
    private String message;

    @JsonProperty("duration_seconds")
    private int durationSeconds;

    @JsonProperty("play_sound")
    private boolean playSound;

    @JsonProperty("sound_text")
    private String soundText;
}
