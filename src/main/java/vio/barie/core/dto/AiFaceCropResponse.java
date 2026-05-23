package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiFaceCropResponse {
    private Integer id;
    private Integer faceId;

    public Integer getActualId() {
        return faceId != null ? faceId : id;
    }
}
