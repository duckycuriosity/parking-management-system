package vio.barie.core.service.filter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vio.barie.core.config.filter.AiQualityProperties;

@Service
@RequiredArgsConstructor
public class AiEventQualityFilter {
  private static final Logger logger = LoggerFactory.getLogger(AiEventQualityFilter.class);
  private final AiQualityProperties properties;

  public QualityCheckResult evaluateFace (JsonNode event) {
    JsonNode quality = event.path("face").path("quality");
    AiQualityProperties.FaceQuality cfg = properties.getFace();

    boolean usable = quality.path("usable").asBoolean(true);
    double qualityScore = quality.path("score").asDouble(0.0);
    double detScore = quality.path("detScore").asDouble(0.0);
    double blurScore = quality.path("blurScore").asDouble(0.0);
    double yaw = Math.abs(quality.path("yawDeg").asDouble(0.0));
    double pitch = Math.abs(quality.path("pitchDeg").asDouble(0.0));

    if (cfg.isRequireUsable() && !usable) {
      return QualityCheckResult.reject("not_usable", 0);
    }
    if (qualityScore < cfg.getMinQualityScore()) {
      return QualityCheckResult.reject("low_quality_score", qualityScore);
    }
    if (detScore < cfg.getMinDetScore()) {
      return QualityCheckResult.reject("low_det_score", detScore);
    }
    if (blurScore < cfg.getMinBlurScore()) {
      return QualityCheckResult.reject("blurry", blurScore);
    }
    if (yaw > cfg.getMaxYawDeg()) {
      return QualityCheckResult.reject("yaw_out_of_range", yaw);
    }
    if (pitch > cfg.getMaxPitchDeg()) {
      return QualityCheckResult.reject("pitch_out_of_range", pitch);
    }

    return QualityCheckResult.accept();
  }

  public QualityCheckResult evaluatePlate (JsonNode plate) {
    double confidence = plate.path("plate").path("confidence").asDouble();
    if (confidence < properties.getPlate().getMinQualityScore()) {
      return QualityCheckResult.reject("low_confidence", confidence);
    }
    return QualityCheckResult.accept();
  }
}
