package vio.barie.core.dto.vms;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LiveStreamRequestDTO {
    private Integer cameraId;
    private Integer channel;
    private String link;
    private String videoCodec;
    private String audioCodec;

    @JsonProperty("videoCodecTag")
    private String videoCodecTag;


}
