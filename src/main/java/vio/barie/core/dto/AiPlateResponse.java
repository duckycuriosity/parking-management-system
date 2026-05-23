package vio.barie.core.dto;

import lombok.Data;

@Data
public class AiPlateResponse {
    private Integer id;
    private String uuid;
    private String plate;
    private String type;
    private Boolean enabled;
}