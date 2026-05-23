package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.dto.LaneResponse;
import vio.barie.core.dto.ParkingSessionDetailResponse;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.ParkingSessionRepository;

import java.util.List;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class ParkingSessionService {

    private final ParkingSessionRepository parkingSessionRepository;
    @Value("${parking.config.pay-per-use-tickets:DAILY,EVENT,VIP}")
    private Set<String> payPerUseTickets;

    public List<ActivitySessionResponse> getTop10ActivitiesByLane(Long laneId) {
        Page<ActivitySessionResponse> page = parkingSessionRepository.getActivityHistoryByLaneId(laneId, PageRequest.of(0, 10));
        return page.getContent();
    }

    public Page<ActivitySessionResponse> getActivitiesByLane(Long laneId, Pageable pageable) {
        return parkingSessionRepository.getActivityHistoryByLaneId(laneId, pageable);
    }

    public Page<ActivitySessionResponse> getAllActivities(Pageable pageable) {
        return parkingSessionRepository.getAllActivityHistory(pageable);
    }
    
    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getAllParkingSessionByParkingLot(
            Long parkingLotId, String username, String sessionType, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String status, Pageable pageable) {


        String filterUsername = (username != null) ? username.trim() : "";
        String filterSessionType = (sessionType != null && !sessionType.trim().isEmpty()) ? sessionType.trim().toUpperCase() : "";
        String filterTypeVehicle = (typeVehicle != null) ? typeVehicle.trim().toUpperCase() : "";
        String filterStatus = (status != null) ? status.trim().toUpperCase() : "";


        Page<Object[]> rawPage = parkingSessionRepository.findAllByParkingLotWithFilters(
                parkingLotId, filterUsername, filterSessionType, filterTypeVehicle, entryLaneId, exitLaneId, filterStatus, pageable);


        return rawPage.map(row -> {
            // Ép kiểu mảng trả về: Vị trí 0 là SessionEntity, vị trí 1 là Ticket Status
            ParkingSessionEntity entity = (ParkingSessionEntity) row[0];
            String ticketStatus = (String) row[1];


            String formattedFee = "0 đ";
            String currentType = entity.getSessionType() != null ? entity.getSessionType().toUpperCase() : "";

            if (payPerUseTickets.contains(currentType)) {
                formattedFee = "Thu phí lượt";
            } else if (!currentType.isEmpty()) {
                formattedFee = "Miễn phí";
            }

            String entryName = entity.getEntryLane() != null ? entity.getEntryLane().getName() : "N/A";
            String exitName = entity.getExitLane() != null ? entity.getExitLane().getName() : "Chưa ra";


            String finalStatus = (ticketStatus != null) ? ticketStatus.toUpperCase() : "UNKNOWN";
            String finalExitLaneName;
            String displayLand;

            if ("ACTIVE".equals(finalStatus)) {
                finalExitLaneName = null;
                displayLand = entryName;
            } else {
                finalExitLaneName = exitName;
                displayLand = entryName + " -> " + exitName;
            }
            return ParkingSessionDetailResponse.builder()
                    .sessionId(entity.getId())
                    .plateNumber(entity.getVehicle() != null ? entity.getVehicle().getPlateNumber() : null)
                    .entryLaneName(entryName)
                    .entryTime(entity.getEntryTime())
                    .exitTime(entity.getExitTime())
                    .sessionType(entity.getSessionType())
                    .typeVehicle(entity.getVehicle() != null ? entity.getVehicle().getVehicleType() : null)
                    .fee(formattedFee)
                    .userName(entity.getEntryCustomer() != null ? entity.getEntryCustomer().getUsername() : "Updating")
                    .land(displayLand)
                    .status(finalStatus)
                    .exitLaneName(finalExitLaneName)
                    .build();
        });
    }

}
