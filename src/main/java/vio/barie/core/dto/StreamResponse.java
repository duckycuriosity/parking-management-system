package vio.barie.core.dto;

import lombok.Data;

@Data
public class StreamResponse {
    private String link;
    private String videoCodec;
    private String audioCodec;
    private String videoCodecTag;
}
