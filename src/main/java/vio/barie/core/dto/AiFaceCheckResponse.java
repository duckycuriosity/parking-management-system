package vio.barie.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiFaceCheckResponse {

    private FaceObject faceObject;
    private Float faceRatio;
    private List<Object> faceAngles;
    private Boolean isMasked;
    private String modelName;
    private List<Float> embedding;
    private List<Object> results;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FaceObject {
        private Float prob;
        private List<Float> box;
        private List<Float> eyes;
        private List<Float> nose;
        private List<Float> mouth;

    }
}
