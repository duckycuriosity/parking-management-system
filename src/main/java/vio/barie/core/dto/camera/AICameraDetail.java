package vio.barie.core.dto.camera;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AICameraDetail {
  @JsonAlias({"cam_id", "id"})
  private String camId;
  private String id;
  private String uuid;
  private String name;
  private String main;
  private String sub;
  private String mode;
  private String protocol;
  private String status;
  private String error;
  private String codec;
  private String hw;
  private Double fps;
  private String mediamtxPath;
  private LiveStream liveStream;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class LiveStream {
    private String audioCodec;
    private Integer channel;
    private String link;
    private String mediamtxPath;
    private String videoCodec;
    private String videoCodecTag;
  }
}
