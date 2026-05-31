package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class VmsLiveStreamResponse {
    private Long cameraId;
    private Integer channel;
    private String link;
    private String videoCodec;
    private String audioCodec;
    @JsonProperty("videoCodecTag")
    private String videoCodecTag;
}
