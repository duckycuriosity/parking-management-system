package vio.barie.core.dto;

import lombok.Data;

@Data
public class AiProfileResponse {
    private Integer id;
    private String name;
    private String uuid;
    private Integer numFace;
    private Integer lastFaceId;
}
