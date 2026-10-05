package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.parking.EventImageInfo;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySessionResponse {
    private Long sessionId;
    private String plateNumber;
    private String vehicleType;
    private List<EventImageInfo> entryImageInfo;
    private List<EventImageInfo> exitImageInfo;
    private SessionType sessionType;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private String parkingLotName;
    private String entryLaneName;
    private String exitLaneName;
    private String cameraName;
}
