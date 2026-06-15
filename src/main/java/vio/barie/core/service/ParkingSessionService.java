package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.InOutStatus;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.dto.ParkingSessionDetailResponse;
import vio.barie.core.dto.parking.HourlyTrafficResponse;
import vio.barie.core.dto.parking.ParkingMetricsRequest;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.ParkingSessionRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingSessionService {

    private final ParkingSessionRepository parkingSessionRepository;
    private final PaymentRepository paymentRepository;

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

    private List<SessionType> parseSessionTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return null; // Trả về null để query bỏ qua filter này (IS NULL)
        }
        List<SessionType> sessionTypes = new ArrayList<>();
        for (String typeStr : types) {
            if (typeStr == null || typeStr.trim().isEmpty()) {
                continue;
            }

            String[] tokens = typeStr.split("[\\s,;]+");

            for (String token : tokens) {
                String cleanToken = token.trim().toUpperCase();
                if (cleanToken.isEmpty()) {
                    continue;
                }
                try {
                    sessionTypes.add(SessionType.valueOf(cleanToken));
                } catch (IllegalArgumentException e) {

                    log.warn("[parseSessionTypes] Giá trị không hợp lệ bị bỏ qua: {}", cleanToken);
                }
            }
        }
        return sessionTypes;
    }

    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getAllParkingSession(
            String searchKey, List<String> sessionTypes, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut, Pageable pageable) {
        List<SessionType> parsedTypes = parseSessionTypes(sessionTypes);
        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findAllParkingLotWithFilters(
                formatString(searchKey), parsedTypes, formatString(typeVehicle),
                entryLaneId, exitLaneId, formatString(statusInOut), pageable);

        Map<Long, PaymentEntity> paymentMap = fetchPaymentMap(entityPage.getContent());
        return entityPage.map(entity -> mapToDetailResponse(entity, paymentMap.get(entity.getId())));
    }


    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getIdParkingSessionByParkingLot(
            Long parkingLotId, String searchKey, List<String> sessionTypes, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut, Pageable pageable) {

        List<SessionType> parsedTypes = parseSessionTypes(sessionTypes);
        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findIdByParkingLotWithFilters(
                parkingLotId, formatString(searchKey), parsedTypes, formatString(typeVehicle),
                entryLaneId, exitLaneId, formatString(statusInOut), pageable);

        Map<Long, PaymentEntity> paymentMap = fetchPaymentMap(entityPage.getContent());
        return entityPage.map(entity -> mapToDetailResponse(entity, paymentMap.get(entity.getId())));
    }

    private Map<Long, PaymentEntity> fetchPaymentMap(List<ParkingSessionEntity> sessions) {
        List<Long> sessionIds = sessions.stream()
                .map(ParkingSessionEntity::getId)
                .collect(Collectors.toList());
        if (sessionIds.isEmpty()) return Collections.emptyMap();

        return paymentRepository.findLatestBySessionIds(sessionIds).stream()
                .filter(p -> p.getCallbackContext() != null && p.getCallbackContext().getLong("sessionId") != null)
                .collect(Collectors.toMap(
                        p -> p.getCallbackContext().getLong("sessionId"),
                        p -> p,
                        (a, b) -> a
                ));
    }

    private String formatString(String input) {
        return (input != null && !input.trim().isEmpty()) ? input.trim().toUpperCase() : null;
    }

    private ParkingSessionDetailResponse mapToDetailResponse(ParkingSessionEntity entity, PaymentEntity payment) {
        SessionType sessionType = entity.getSessionType();
        String formattedFee = "0 đ";

        if (sessionType == SessionType.MONTH || sessionType == SessionType.QUARTER) {
            formattedFee = "Miễn phí";
        } else if (payment != null && payment.getAmount() != null) {
            formattedFee = String.format("%,.0f đ", payment.getAmount());
        } else {
            formattedFee = "Chưa thanh toán";
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
    public List<HourlyTrafficResponse> getHourlyStatistics(ParkingMetricsRequest request) {
        // Lấy dữ liệu từ repository
        List<Object[]> entriesResult = parkingSessionRepository.countEntriesByHour(
                request.getParkingLotId(),
                request.getStartDate(),
                request.getEndDate()
        );

        List<Object[]> exitsResult = parkingSessionRepository.countExitsByHour(
                request.getParkingLotId(),
                request.getStartDate(),
                request.getEndDate()
        );

        // Chuyển đổi sang Map với key là datetime đầy đủ
        Map<String, Long> entriesMap = new HashMap<>();
        for (Object[] row : entriesResult) {
            String datetime = (String) row[0]; // "2026-06-13 18:00:00"
            Long count = ((Number) row[1]).longValue();
            entriesMap.put(datetime, count);
        }

        Map<String, Long> exitsMap = new HashMap<>();
        for (Object[] row : exitsResult) {
            String datetime = (String) row[0];
            Long count = ((Number) row[1]).longValue();
            exitsMap.put(datetime, count);
        }

        // Tạo list các datetime trong khoảng
        List<String> allDateTimes = generateDateTimeRange(
                request.getStartDate(),
                request.getEndDate()
        );

        // Build response
        List<HourlyTrafficResponse> response = new ArrayList<>();
        long runningParked = 0;

        for (String dateTime : allDateTimes) {
            long entries = entriesMap.getOrDefault(dateTime, 0L);
            long exits = exitsMap.getOrDefault(dateTime, 0L);

            runningParked = runningParked + entries - exits;
            if (runningParked < 0) runningParked = 0;

            HourlyTrafficResponse dto = new HourlyTrafficResponse();
            dto.setDateTime(dateTime);                    // "2026-06-13 18:00"
            dto.setDate(dateTime.substring(0, 10));       // "2026-06-13"
            dto.setHour(dateTime.substring(11, 16));      // "18:00"
            dto.setEntries(entries);
            dto.setExits(exits);
            dto.setCurrentParked(runningParked);

            response.add(dto);
        }

        return response;
    }

    private List<String> generateDateTimeRange(LocalDateTime start, LocalDateTime end) {
        List<String> dateTimes = new ArrayList<>();
        LocalDateTime current = start.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime endHour = end.truncatedTo(ChronoUnit.HOURS);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00");

        while (!current.isAfter(endHour)) {
            dateTimes.add(current.format(formatter));
            current = current.plusHours(1);
        }
        return dateTimes;
    }

}
