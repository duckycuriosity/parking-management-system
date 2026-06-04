package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.InOutStatus;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.dto.ParkingSessionDetailResponse;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.ParkingSessionRepository;

import java.util.List;


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

    private SessionType parseTicketType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return null; // Trả về null để query bỏ qua filter này (IS NULL)
        }
        try {
            return SessionType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // Nếu client gửi type vớ vẩn thì bỏ qua filter
        }
    }

    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getAllParkingSession(
            String searchKey, String sessionType, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut, Pageable pageable) {

        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findAllParkingLotWithFilters(
                formatString(searchKey), parseTicketType(sessionType), formatString(typeVehicle),
                entryLaneId, exitLaneId, formatString(statusInOut), pageable);

        return entityPage.map(this::mapToDetailResponse);
    }


    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getIdParkingSessionByParkingLot(
            Long parkingLotId, String searchKey, String sessionType, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut, Pageable pageable) {

        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findIdByParkingLotWithFilters(
                parkingLotId, formatString(searchKey), parseTicketType(sessionType), formatString(typeVehicle),
                entryLaneId, exitLaneId, formatString(statusInOut), pageable);

        return entityPage.map(this::mapToDetailResponse);
    }

    private String formatString(String input) {
        return (input != null && !input.trim().isEmpty()) ? input.trim().toUpperCase() : null;
    }

    private ParkingSessionDetailResponse mapToDetailResponse(ParkingSessionEntity entity) {
        SessionType sessionType = entity.getSessionType();
        String formattedFee = "0 đ";

        if (sessionType != null) {
            if (sessionType == SessionType.DAY) {
                formattedFee = "Vé lượt";
            } else if (sessionType == SessionType.MONTH || sessionType == SessionType.QUARTER) {
                formattedFee = "Miễn phí";
            }
        }

        String entryName = entity.getEntryLane() != null ? entity.getEntryLane().getName() : "N/A";
        String exitName = entity.getExitLane() != null ? entity.getExitLane().getName() : "Not found";


        InOutStatus finalStatus = (entity.getExitTime() == null) ? InOutStatus.IN : InOutStatus.OUT;
        String finalExitLaneName = (entity.getExitTime() == null) ? null : exitName;
        String displayLand = (entity.getExitTime() == null) ? entryName : entryName + " -> " + exitName;
        String buildingNameStr = null;
        if (entity.getParkingLot() != null && entity.getParkingLot().getBuilding() != null) {
            buildingNameStr = entity.getParkingLot().getBuilding().getName();
        }

        return ParkingSessionDetailResponse.builder()
                .sessionId(entity.getId())
                .plateNumber(entity.getVehicle() != null ? entity.getVehicle().getPlateNumber() : null)
                .entryLaneName(entryName)
                .entryTime(entity.getEntryTime())
                .exitTime(entity.getExitTime())
                .sessionType(sessionType) // truyền enum
                .typeVehicle(entity.getVehicle() != null ? entity.getVehicle().getVehicleType() : null)
                .fee(formattedFee)
                .userName(entity.getEntryCustomer() != null ? entity.getEntryCustomer().getUsername() : "Updating")
                .land(displayLand)
                .statusInOut(finalStatus) // truyền enum
                .exitLaneName(finalExitLaneName)
                .buildingName(buildingNameStr)
                .build();
    }

}
