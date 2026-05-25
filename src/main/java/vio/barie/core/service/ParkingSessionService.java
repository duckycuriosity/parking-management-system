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
            Long parkingLotId, String searchKey, String sessionType, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut, Pageable pageable) {


        String filterSearch = (searchKey != null) ? searchKey.trim() : "";
        String filterSessionType = (sessionType != null && !sessionType.trim().isEmpty()) ? sessionType.trim().toUpperCase() : "";
        String filterTypeVehicle = (typeVehicle != null) ? typeVehicle.trim().toUpperCase() : "";
        String filterStatusInOut = (statusInOut != null) ? statusInOut.trim().toUpperCase() : "";


        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findAllByParkingLotWithFilters(
                parkingLotId, filterSearch, filterSessionType, filterTypeVehicle, entryLaneId, exitLaneId, filterStatusInOut, pageable);


        return entityPage.map(entity -> {
            // Ép kiểu mảng trả về: Vị trí 0 là SessionEntity, vị trí 1 là Ticket Status


            String formattedFee = "0 đ";
            String currentType = entity.getSessionType() != null ? entity.getSessionType().toUpperCase() : "";

            if (payPerUseTickets.contains(currentType)) {
                formattedFee = "Thu phí lượt";
            } else if (!currentType.isEmpty()) {
                formattedFee = "Miễn phí";
            }

            String entryName = entity.getEntryLane() != null ? entity.getEntryLane().getName() : "N/A";
            String exitName = entity.getExitLane() != null ? entity.getExitLane().getName() : "Chưa ra";


            String finalStatus;
            String finalExitLaneName;
            String displayLand;

            if (entity.getExitTime() == null) {
                // Đang trong bãi (Chỉ có Entry, chưa có Exit)
                finalStatus = "IN";
                finalExitLaneName = null;
                displayLand = entryName;
            } else {
                // Đã ra khỏi bãi (Có cả Entry và Exit)
                finalStatus = "OUT";
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
                    .statusInOut(finalStatus)
                    .exitLaneName(finalExitLaneName)
                    .build();
        });
    }

}
