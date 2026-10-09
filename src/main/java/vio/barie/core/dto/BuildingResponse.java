package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.OperatingMode;

import java.time.LocalDateTime;

@Data
public class BuildingResponse {
    private Long id;
    private String name;
    private String address;
    private String description;
    private String phoneNumber;
    private String buildingCode;
    private String managementUnit;
    private BaseStatus status;
    private OperatingMode operatingMode;
    private LocalDateTime createdAt;
}