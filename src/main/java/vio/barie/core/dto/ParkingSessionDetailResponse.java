package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ParkingSessionDetailResponse {
    private Long sessionId;
    private String plateNumber;
    private String userName;
    private String entryLaneName;
    private String exitLaneName;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private String sessionType;
    private String typeVehicle;
    private String fee;
    private String land;
    private String statusInOut;
}
