package vio.barie.core.config.filter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ai.quality")
@Data
public class AiQualityProperties {
  @Data
  public static class FaceQuality {
    private double minQualityScore = 0.3;
    private double minDetScore = 0.4;
    private double minBlurScore = 10;
//    private double maxYawDeg = 45;
//    private double maxPitchDeg = 45;
    private boolean requireUsable = true;
  }
  @Data
  public static class PlateQuality {
    private double minQualityScore = 0.5;
  }

  private FaceQuality face = new FaceQuality();
  private PlateQuality plate = new PlateQuality();
}
