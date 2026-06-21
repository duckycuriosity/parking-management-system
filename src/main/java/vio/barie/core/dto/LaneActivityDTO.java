package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LaneActivityDTO {
    private Integer laneId;
    private String laneName;
    private String laneType;
    private LocalDateTime lastActionTime;
    private Integer parkingLotId;
    private Integer vehicleId;
    private String plateNumber;
    private String vehicleType;
}
