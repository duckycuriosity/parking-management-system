package vio.barie.core.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BuildingResponse {
    private Long id;
    private String name;
    private String address;
    private LocalDateTime createdAt;
}