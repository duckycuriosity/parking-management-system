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
import vio.barie.core.dto.parking.CurrentParkingSessionCountResponse;
import vio.barie.core.dto.parking.HourlyTrafficResponse;
import vio.barie.core.dto.parking.ParkingLotCapacityItemResponse;
import vio.barie.core.dto.parking.ParkingLotCapacityProjection;
import vio.barie.core.dto.parking.ParkingLotCapacityResponse;
import vio.barie.core.dto.parking.ParkingMetricsRequest;
import vio.barie.core.dto.parking.VehicleMetricsResponse;
import vio.barie.core.dto.*;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.ParkingLotRepository;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.utils.VehicleType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingSessionService {

    private final ParkingSessionRepository parkingSessionRepository;
    private final PaymentRepository paymentRepository;
    private final ParkingLotRepository parkingLotRepository;

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
        return sessionTypes.isEmpty() ? null : sessionTypes;
    }

    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getAllParkingSession(
            String searchKey, Long parkingLotId, List<String> sessionTypes, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        List<SessionType> parsedTypes = parseSessionTypes(sessionTypes);
        LocalDateTime from = startDate != null ? startDate : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime to = endDate != null ? endDate : LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findAllParkingLotWithFilters(
                formatString(searchKey), parkingLotId, parsedTypes, formatString(typeVehicle),
                entryLaneId, exitLaneId, formatString(statusInOut), from, to, pageable);

        Map<Long, PaymentEntity> paymentMap = fetchPaidPaymentMap(entityPage.getContent());
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

        Map<Long, PaymentEntity> paymentMap = fetchPaidPaymentMap(entityPage.getContent());
        return entityPage.map(entity -> mapToDetailResponse(entity, paymentMap.get(entity.getId())));
    }

    @Transactional(readOnly = true)
    public Page<ParkingSessionPaymentDetailResponse> getParkingSessionPaymentDetailsByParkingLot(
            Long parkingLotId, String searchKey, List<String> sessionTypes, String typeVehicle,
            Long entryLaneId, Long exitLaneId, String statusInOut,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {

        List<SessionType> parsedTypes = parseSessionTypes(sessionTypes);
        LocalDateTime from = startDate != null ? startDate : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime to = endDate != null ? endDate : LocalDateTime.of(9999, 12, 31, 23, 59, 59);

        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findPaymentDetailSessionsWithFilters(
                parkingLotId, formatString(searchKey), parsedTypes, formatString(typeVehicle),
                entryLaneId, exitLaneId, formatString(statusInOut), from, to, pageable);

        Map<Long, PaymentEntity> paymentMap = fetchAnyStatusPaymentMap(entityPage.getContent());
        return entityPage.map(entity -> mapToPaymentDetailResponse(entity, paymentMap.get(entity.getId())));
    }

    @Transactional(readOnly = true)
    public Page<ParkingSessionDetailResponse> getInOutSessions(
            Long buildingId, Long parkingLotId, Long laneId, Pageable pageable) {

        Page<ParkingSessionEntity> entityPage = parkingSessionRepository.findInOutSessions(
                buildingId, parkingLotId, laneId, pageable);

        Map<Long, PaymentEntity> paymentMap = fetchPaidPaymentMap(entityPage.getContent());
        return entityPage.map(entity -> mapToDetailResponse(entity, paymentMap.get(entity.getId())));
    }

    private Map<Long, PaymentEntity> fetchPaidPaymentMap(List<ParkingSessionEntity> sessions) {
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

    private Map<Long, PaymentEntity> fetchAnyStatusPaymentMap(List<ParkingSessionEntity> sessions) {
        List<Long> sessionIds = sessions.stream()
                .map(ParkingSessionEntity::getId)
                .collect(Collectors.toList());
        if (sessionIds.isEmpty()) return Collections.emptyMap();

        return paymentRepository.findLatestAnyStatusBySessionIds(sessionIds).stream()
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
        String parkingLotNameStr = null;
        if (entity.getParkingLot() != null && entity.getParkingLot().getBuilding() != null) {
            buildingNameStr = entity.getParkingLot().getBuilding().getName();
        }
        if (entity.getParkingLot() != null) {
            parkingLotNameStr = entity.getParkingLot().getName();
        }
        String location = buildLocation(buildingNameStr, parkingLotNameStr);

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
                .parkingLotName(parkingLotNameStr)
                .location(location)
                .entryImageInfo(entity.getEntryImageInfo())
                .exitImageInfo(entity.getExitImageInfo())
                .build();
    }

    private ParkingSessionPaymentDetailResponse mapToPaymentDetailResponse(ParkingSessionEntity entity, PaymentEntity payment) {
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
        String parkingLotNameStr = null;
        if (entity.getParkingLot() != null && entity.getParkingLot().getBuilding() != null) {
            buildingNameStr = entity.getParkingLot().getBuilding().getName();
        }
        if (entity.getParkingLot() != null) {
            parkingLotNameStr = entity.getParkingLot().getName();
        }
        String location = buildLocation(buildingNameStr, parkingLotNameStr);

        return ParkingSessionPaymentDetailResponse.builder()
                .sessionId(entity.getId())
                .plateNumber(entity.getVehicle() != null ? entity.getVehicle().getPlateNumber() : null)
                .entryLaneName(entryName)
                .entryTime(entity.getEntryTime())
                .exitTime(entity.getExitTime())
                .parkingDuration(formatParkingDuration(entity.getEntryTime(), entity.getExitTime()))
                .sessionType(sessionType)
                .typeVehicle(entity.getVehicle() != null ? entity.getVehicle().getVehicleType() : null)
                .fee(formattedFee)
                .userName(entity.getEntryCustomer() != null ? entity.getEntryCustomer().getUsername() : "Updating")
                .land(displayLand)
                .statusInOut(finalStatus)
                .exitLaneName(finalExitLaneName)
                .buildingName(buildingNameStr)
                .parkingLotName(parkingLotNameStr)
                .location(location)
                .entryImageInfo(entity.getEntryImageInfo())
                .exitImageInfo(entity.getExitImageInfo())
                .paymentId(payment != null ? payment.getId() : null)
                .invoiceNo(payment != null ? payment.getInvoiceNo() : null)
                .paymentAmount(payment != null ? payment.getAmount() : null)
                .paymentAmountText(payment != null && payment.getAmount() != null ? String.format("%,.0f đ", payment.getAmount()) : null)
                .currency(payment != null ? payment.getCurrency() : null)
                .paymentMethod(payment != null ? payment.getPaymentMethod() : null)
                .paidAt(payment != null ? payment.getPaidAt() : null)
                .paymentStatus(payment != null ? payment.getStatus() : null)
                .resultCode(payment != null ? payment.getResultCode() : null)
                .resultMessage(payment != null ? payment.getResultMessage() : null)
                .payType(payment != null ? payment.getPayType() : null)
                .paymentDescription(payment != null ? payment.getDescription() : null)
                .gateway(payment != null ? payment.getGateway() : null)
                .referenceCode(payment != null ? payment.getReferenceCode() : null)
                .sepayTxId(payment != null ? payment.getSepayTxId() : null)
                .build();
    }

    private String buildLocation(String buildingName, String parkingLotName) {
        if (buildingName != null && parkingLotName != null) {
            return buildingName + ", " + parkingLotName;
        }
        if (buildingName != null) {
            return buildingName;
        }
        return parkingLotName;
    }

    private String formatParkingDuration(LocalDateTime entryTime, LocalDateTime exitTime) {
        if (entryTime == null) {
            return null;
        }

        LocalDateTime endTime = exitTime != null ? exitTime : LocalDateTime.now();
        long totalSeconds = Duration.between(entryTime, endTime).getSeconds();
        if (totalSeconds < 0) {
            totalSeconds = 0;
        }

        long days = totalSeconds / (24 * 60 * 60);
        long hours = (totalSeconds % (24 * 60 * 60)) / (60 * 60);
        long minutes = (totalSeconds % (60 * 60)) / 60;
        long seconds = totalSeconds % 60;

        if (days > 0) return String.format("%dd%dh%dm%ds", days, hours, minutes, seconds);
        if (hours > 0) return String.format("%dh%dm%ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm%ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    public VehicleMetricsResponse getVehicleMetrics(Integer buildingId) {
        VehicleMetricsResponse result = new VehicleMetricsResponse();
        result.setCars(parkingSessionRepository.countNumberOfVehicleInParkingLot(buildingId, VehicleType.CAR.name()));
        result.setMotorbikes(parkingSessionRepository.countNumberOfVehicleInParkingLot(buildingId, VehicleType.MOTORBIKE.name()));
        result.setUnidentifiedVehicles(parkingSessionRepository.countNumberOfVehicleInParkingLot(buildingId, "OTHER"));
        return result;
    }

    public List<HourlyTrafficResponse> getHourlyStatistics(ParkingMetricsRequest request) {
        // Lấy dữ liệu từ repository
        List<Object[]> entriesResult = parkingSessionRepository.countEntriesByHour(
                request.getBuildingId(),
                request.getParkingLotId(),
                request.getStartDate(),
                request.getEndDate()
        );

        List<Object[]> exitsResult = parkingSessionRepository.countExitsByHour(
                request.getBuildingId(),
                request.getParkingLotId(),
                request.getStartDate(),
                request.getEndDate()
        );

        Map<String, Long> entriesMap = new HashMap<>();
        for (Object[] row : entriesResult) {
            String datetime = (String) row[0];
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
            dto.setDateTime(dateTime);
            dto.setDate(dateTime.substring(0, 10));
            dto.setHour(dateTime.substring(11, 16));
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

    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStatistics(
            Long buildingId, Long parkingLotId) {


        // thống kê các loại xe
        List<VehicleStatProjection> vehicleStatsList = parkingSessionRepository.countVehicleTypeStats(buildingId, parkingLotId);
        DashboardStatsResponse.VehicleStats vehicleStats = new DashboardStatsResponse.VehicleStats();

        for (VehicleStatProjection proj : vehicleStatsList) {
            if (proj.getVehicleType() == null) continue;
            String type = proj.getVehicleType().toUpperCase();
            long total = proj.getTotal() != null ? proj.getTotal() : 0;

            switch (type) {
                case "MOTORBIKE":
                    vehicleStats.setTotalMotorbike(total);
                    break;
                case "CAR":
                    vehicleStats.setTotalCar(total);
                    break;
                default:
                    vehicleStats.setTotalOther(vehicleStats.getTotalOther() + total);
                    break;
            }
        }


        // tính toán sức chứa
        DashboardStatsResponse.VehicleTypeBreakdown motorbikeBreakdown =
                DashboardStatsResponse.VehicleTypeBreakdown.builder().build();
        DashboardStatsResponse.VehicleTypeBreakdown carBreakdown =
                DashboardStatsResponse.VehicleTypeBreakdown.builder().build();

        List<SessionStatProjection> currentSessionStats =
                parkingSessionRepository.countCurrentSessionAndVehicleStats(buildingId, parkingLotId);
        for (SessionStatProjection stat : currentSessionStats) {
            if (stat.getVehicleType() == null || stat.getSessionType() == null) continue;

            long total = stat.getTotal() != null ? stat.getTotal() : 0;
            DashboardStatsResponse.VehicleTypeBreakdown breakdown = null;
            if ("MOTORBIKE".equalsIgnoreCase(stat.getVehicleType())) {
                breakdown = motorbikeBreakdown;
            } else if ("CAR".equalsIgnoreCase(stat.getVehicleType())) {
                breakdown = carBreakdown;
            }

            if (breakdown == null) continue;
            breakdown.setTotal(breakdown.getTotal() + total);
            if (isSubscriptionSession(stat.getSessionType())) {
                breakdown.setSubscription(breakdown.getSubscription() + total);
            } else if (SessionType.DAY.name().equalsIgnoreCase(stat.getSessionType())) {
                breakdown.setWalkIn(breakdown.getWalkIn() + total);
            }
        }
        vehicleStats.setMotorbike(motorbikeBreakdown);
        vehicleStats.setCar(carBreakdown);

        long maxMotorcycle = 0;
        long maxCar = 0;
        List<Object[]> capacityResult = parkingLotRepository.getCapacityStats(buildingId, parkingLotId);
        if (!capacityResult.isEmpty() && capacityResult.get(0).length == 2) {
            maxMotorcycle = ((Number) capacityResult.get(0)[0]).longValue();
            maxCar = ((Number) capacityResult.get(0)[1]).longValue();
        }

        // validate vượt sức chứa
        boolean isFull = false;
        String warningMessage = null;

        boolean isMotorbikeFull = maxMotorcycle >= 0 && vehicleStats.getTotalMotorbike() >= maxMotorcycle;
        boolean isCarFull = maxCar >= 0 && vehicleStats.getTotalCar() >= maxCar;
        if (isMotorbikeFull && isCarFull) {
            isFull = true;
            warningMessage = "Bãi gửi xe đã hết chỗ (cả ô tô và xe máy), vui lòng qua bãi gửi xe khác.";
        } else if (isMotorbikeFull) {
            isFull = true;
            warningMessage = "Bãi gửi xe đã hết chỗ cho Xe Máy, vui lòng qua bãi gửi xe khác.";
        } else if (isCarFull) {
            isFull = true;
            warningMessage = "Bãi gửi xe đã hết chỗ cho Ô Tô, vui lòng qua bãi gửi xe khác.";
        }
        return DashboardStatsResponse.builder()

                .vehicleStats(vehicleStats)
                .maxMotorcycleCapacity(maxMotorcycle)
                .maxCarCapacity(maxCar)
                .isFull(isFull)
                .warningMessage(warningMessage)
                .build();
    }

    private boolean isSubscriptionSession(String sessionType) {
        return SessionType.MONTH.name().equalsIgnoreCase(sessionType)
                || SessionType.QUARTER.name().equalsIgnoreCase(sessionType);
    }

    @Transactional
    public DashboardStatsResponseInOut getDashboardStatisInOut(
            Long buildingId, Long parkingLotId
    ) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay(); // 00:00
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX); // 23:59:59

        // tính toán traffic ra vào
        long totalEntry = parkingSessionRepository.countTotalEntries(buildingId, parkingLotId, startOfDay, endOfDay);
        long totalExit = parkingSessionRepository.countTotalExits(buildingId, parkingLotId, startOfDay, endOfDay);

        // thống kê phiên
        long totalSessionTypeCount = parkingSessionRepository.countTotalSessionTypes(buildingId, parkingLotId, startOfDay, endOfDay);
        // lấy danh sách loại phiên, loại xe
        List<SessionStatProjection> sessionStatsList = parkingSessionRepository.countSessionAndVehicleStats(buildingId, parkingLotId, startOfDay, endOfDay);

        long dayCar = 0, dayMotorbike = 0;
        long monthCar = 0, monthMotorbike = 0;
        long quarterCar = 0, quarterMotorbike = 0;

        for (SessionStatProjection stat : sessionStatsList) {
            String sessionType = stat.getSessionType(); // DAY, MONTH, QUARTER
            String vehicleType = stat.getVehicleType(); // CAR, MOTORBIKE
            long count = stat.getTotal() != null ? stat.getTotal() : 0;

            if ("CAR".equalsIgnoreCase(vehicleType)) {
                if ("DAY".equals(sessionType)) dayCar = count;
                else if ("MONTH".equals(sessionType)) monthCar = count;
                else if ("QUARTER".equals(sessionType)) quarterCar = count;

            } else if ("MOTORBIKE".equalsIgnoreCase(vehicleType)) {
                if ("DAY".equals(sessionType)) dayMotorbike = count;
                else if ("MONTH".equals(sessionType)) monthMotorbike = count;
                else if ("QUARTER".equals(sessionType)) quarterMotorbike = count;
            }
        }
        return DashboardStatsResponseInOut.builder()
                .totalTraffic(totalEntry + totalExit)
                .totalEntry(totalEntry)
                .totalExit(totalExit)
                .totalSessionTypeCount(totalSessionTypeCount)

                .dayCar(dayCar)
                .dayMotorbike(dayMotorbike)
                .totalSessionDay(dayCar + dayMotorbike)

                .monthCar(monthCar)
                .monthMotorbike(monthMotorbike)
                .totalSessionMonth(monthCar + monthMotorbike)

                .quarterCar(quarterCar)
                .quarterMotorbike(quarterMotorbike)
                .totalSessionQuarter(quarterCar + quarterMotorbike)

                .totalSessionMonThAndQuarterCar(monthCar + quarterCar)
                .totalSessionMonThAndQuarterMotorbike(monthMotorbike + quarterMotorbike)
                .totalSessionMonthAndQuarter(monthCar + quarterCar + monthMotorbike + quarterMotorbike)
                .build();
    }

    public CurrentParkingSessionCountResponse getCurrentParkingSessionCount(Integer buildingId, Integer parkingLotId) {
        return parkingSessionRepository.currentParkingSessionCount(buildingId, parkingLotId);
    }

    @Transactional(readOnly = true)
    public ParkingLotCapacityResponse getCapacityByLot(Long buildingId, Long parkingLotId) {
        List<ParkingLotCapacityProjection> capacityRows = parkingLotRepository.getCapacityByLot(buildingId, parkingLotId);

        List<ParkingLotCapacityItemResponse> lots = capacityRows.stream()
                .map(this::mapCapacityItem)
                .collect(Collectors.toList());

        long totalCapacity = lots.stream()
                .mapToLong(ParkingLotCapacityItemResponse::getTotalCapacity)
                .sum();
        long totalUsed = lots.stream()
                .mapToLong(ParkingLotCapacityItemResponse::getUsed)
                .sum();

        return ParkingLotCapacityResponse.builder()
                .totalCapacity(totalCapacity)
                .totalUsed(totalUsed)
                .totalAvailable(Math.max(totalCapacity - totalUsed, 0))
                .usageRate(calculateUsageRate(totalUsed, totalCapacity))
                .lots(lots)
                .build();
    }

    private ParkingLotCapacityItemResponse mapCapacityItem(ParkingLotCapacityProjection row) {
        long motorbikeCapacity = safeLong(row.getMotorbikeCapacity());
        long carCapacity = safeLong(row.getCarCapacity());
        long totalCapacity = motorbikeCapacity + carCapacity;
        long used = safeLong(row.getUsedTotal());

        return ParkingLotCapacityItemResponse.builder()
                .parkingLotId(row.getParkingLotId())
                .parkingLotName(row.getParkingLotName())
                .totalCapacity(totalCapacity)
                .used(used)
                .available(Math.max(totalCapacity - used, 0))
                .usageRate(calculateUsageRate(used, totalCapacity))
                .motorbikeCapacity(motorbikeCapacity)
                .carCapacity(carCapacity)
                .usedMotorbike(safeLong(row.getUsedMotorbike()))
                .usedCar(safeLong(row.getUsedCar()))
                .usedOther(safeLong(row.getUsedOther()))
                .build();
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private double calculateUsageRate(long used, long capacity) {
        if (capacity <= 0) {
            return 0D;
        }
        return Math.round((used * 10000D) / capacity) / 100D;
    }
}
