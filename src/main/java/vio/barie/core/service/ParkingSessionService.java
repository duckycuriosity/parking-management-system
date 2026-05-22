package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
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

    private static final Set<String> PAY_PER_USE_TICKETS = Set.of("DAILY", "GUEST", "EVENT");

    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getParkingSessionsForManagement(
            Boolean activeOnly, String username, String sessionType, String typeVehicle, Long entryLaneId, Long exitLaneId, Pageable pageable) {


        String filterUsername = (username != null) ? username.trim() : "";

        String filterSessionType = (sessionType != null && !sessionType.trim().isEmpty())
                ? sessionType.trim().toUpperCase()
                : "";

        String filterTypeVehicle = (typeVehicle != null) ? typeVehicle.trim().toUpperCase() : "";


        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findAllWithFilters(
                activeOnly, filterUsername, filterSessionType, filterTypeVehicle, entryLaneId, exitLaneId, pageable);

        return entityPage.map(entity -> {
            String formattedFee = "0 đ";
            String currentType = entity.getSessionType() != null ? entity.getSessionType().toUpperCase() : "";

            if (PAY_PER_USE_TICKETS.contains(currentType)) {
                String vType = entity.getVehicle() != null ? entity.getVehicle().getVehicleType() : "";
                formattedFee = "CAR".equalsIgnoreCase(vType) ? "15.000 đ" : "5.000 đ";
            } else if (!currentType.isEmpty()) {
                formattedFee = "Free";
            }
            String entryName = entity.getEntryLane() != null ? entity.getEntryLane().getName() : "N/A";
            String exitName = entity.getExitLane() != null ? entity.getExitLane().getName() : "Chưa ra";

            return ParkingSessionDetailResponse.builder()
                    .sessionId(entity.getId())
                    .plateNumber(entity.getVehicle() != null ? entity.getVehicle().getPlateNumber() : null)
                    .entryLaneName(entity.getEntryLane() != null ? entity.getEntryLane().getName() : null)
                    .entryTime(entity.getEntryTime())
                    .exitTime(entity.getExitTime())
                    .sessionType(entity.getSessionType())
                    .typeVehicle(entity.getVehicle() != null ? entity.getVehicle().getVehicleType() : null)
                    .fee(formattedFee)
                    .userName(entity.getEntryCustomer() != null ? entity.getEntryCustomer().getUsername() : "Updating")
                    .land(entryName + " -> " + exitName)
                    .build();


        });
    }
}
