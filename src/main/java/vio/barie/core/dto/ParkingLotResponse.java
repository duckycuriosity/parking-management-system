package vio.barie.core.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ParkingLotResponse {
    private Long id;
    private String name;
    private Long buildingId;
    private LocalDateTime createdAt;
}
