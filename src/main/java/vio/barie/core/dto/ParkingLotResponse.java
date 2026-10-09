package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.OperatingMode;
import vio.barie.core.common.VerificationMode;

import java.time.LocalDateTime;

@Data
public class ParkingLotResponse {
    private Long id;
    private String name;
    private Long buildingId;
    private String buildingName;
    private String description;
    private BaseStatus status;
    private OperatingMode operatingMode;
    private LocalDateTime createdAt;
    private Integer maxMotorcycleCapacity;
    private Integer maxCarCapacity;
    private VerificationMode verificationMode;
    private String parkingLotCode;
}
