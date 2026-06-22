package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LaneActivityDTO {
    private Long laneId;
    private String laneName;
    private String laneType;
    private String lastAction;
    private LocalDateTime lastActionTime;
    private Long parkingLotId;
    private Long vehicleId;
    private String plateNumber;
    private String vehicleType;
}
