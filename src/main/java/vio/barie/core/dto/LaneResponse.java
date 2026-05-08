package vio.barie.core.dto;

import lombok.Data;

@Data
public class LaneResponse {
    private Long id;
    private String name;
    private String type;
    private String status;
    private Long parkingLotId;
    private java.time.LocalDateTime createdAt;
}