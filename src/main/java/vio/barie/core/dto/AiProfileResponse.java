package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiProfileResponse {
    private Integer id;
    private String name;
    private String uuid;
    private Integer numFace;
    private Integer lastFaceId;
    @JsonProperty("profileId")
    private Integer aiProfileId;
}
