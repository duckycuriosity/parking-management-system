package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.common.BaseStatus;

import java.time.LocalDateTime;

@Data
public class BuildingResponse {
    private Long id;
    private String name;
    private String address;
    private String description;
    private BaseStatus status;
    private LocalDateTime createdAt;
}