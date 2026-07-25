package vio.barie.core.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AICameraMode {
  LPR("LPR"),
  FACE_RECOGNITION("Face recognition"),
  LPR_FACE("LPR + Face"),
  ALL_MODES("All modes");

  private final String aiValue;

  AICameraMode(String aiValue) {
    this.aiValue = aiValue;
  }

  @JsonValue
  public String getAiValue() {
    return aiValue;
  }

  @JsonCreator
  public static AICameraMode fromAiValue(String value) {
    for (AICameraMode mode: values()) {
      if (mode.aiValue.equalsIgnoreCase(value)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("Mode is not illegal, only accept those values: LPR, Face recognition, LPR + Face and All modes");
  }
}
