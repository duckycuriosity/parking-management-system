package vio.barie.core.dto.camera;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AICameraActionResponse {
  @JsonProperty("cam_id")
  private String camId;
  private Boolean ok;
  private String status;
  private String uuid;
}
