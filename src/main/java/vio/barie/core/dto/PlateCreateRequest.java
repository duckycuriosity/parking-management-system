package vio.barie.core.dto;

import lombok.Data;

@Data
public class PlateCreateRequest {
    private String plate;
    private String type;
    private Boolean enabled;
}
