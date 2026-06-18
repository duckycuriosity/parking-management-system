package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.common.BaseStatus;

import java.time.LocalDateTime;

@Data
public class ParkingLotResponse {
    private Long id;
    private String name;
    private Long buildingId;
    private String buildingName;
    private String description;
    private BaseStatus status;
    private LocalDateTime createdAt;
    private Integer maxMotorcycleCapacity;
    private Integer maxCarCapacity;
}
