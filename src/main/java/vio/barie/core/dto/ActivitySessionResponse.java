package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySessionResponse {
    private Long sessionId;
    private String plateNumber;
    private String vehicleType;

    private LocalDateTime entryTime;
    private LocalDateTime exitTime;

    private String parkingLotName;
    private String exitLaneName;
    private String entryLaneName;

    private String cameraName;
}
