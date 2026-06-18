package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.entity.VehicleEntity;

@Data
public class AiPlateResponse {
    private Integer id;
    private String uuid;
    private String plate;
    private String type;
    private Boolean enabled;
    private VehicleEntity vehicle;
}