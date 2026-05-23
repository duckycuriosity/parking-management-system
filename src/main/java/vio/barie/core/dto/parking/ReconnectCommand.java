package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReconnectCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "reconnect";

    @JsonProperty("reason")
    private String reason;              // "server_restart"

    @JsonProperty("timestamp")
    private String timestamp;
}
