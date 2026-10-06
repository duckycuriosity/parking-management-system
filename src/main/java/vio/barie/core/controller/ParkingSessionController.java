package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.DashboardStatsResponse;
import vio.barie.core.dto.DashboardStatsResponseInOut;
import vio.barie.core.dto.ParkingSessionDetailResponse;
import vio.barie.core.dto.ParkingSessionPaymentDetailResponse;
import vio.barie.core.service.ParkingSessionService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/parking-sessions")
@RequiredArgsConstructor
public class ParkingSessionController {
    private final ParkingSessionService parkingSessionService;


    @GetMapping("/in-out")
    public ResponseEntity<Page<ParkingSessionDetailResponse>> getInOutSessions(
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "parkingLotId", required = false) Long parkingLotId,
            @RequestParam(value = "laneId", required = false) Long laneId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "entryTime"));
        Page<ParkingSessionDetailResponse> response = parkingSessionService.getInOutSessions(
                buildingId, parkingLotId, laneId, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<ParkingSessionDetailResponse>> getAllParkingSessions(
            @RequestParam(value = "searchKey", required = false) String searchKey,
            @RequestParam(value = "parkingLotId", required = false) Long parkingLotId,
            @RequestParam(value = "sessionType", required = false) List<String> sessionType,
            @RequestParam(value = "typeVehicle", required = false) String typeVehicle,
            @RequestParam(value = "entryLaneId", required = false) Long entryLaneId,
            @RequestParam(value = "exitLaneId", required = false) Long exitLaneId,
            @RequestParam(value = "statusInOut", required = false) String statusInOut,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size

    ) {
        log.info("Xem log ở đây [getAllParkingSessions] RAW PARAMS → searchKey='{}', sessionType='{}', " +
                        "typeVehicle='{}', entryLaneId={}, exitLaneId={}, statusInOut='{}', page={}, size={}",
                searchKey, sessionType, typeVehicle, entryLaneId, exitLaneId, statusInOut, page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "entryTime"));
        log.info("[getAllParkingSessions] Pageable → pageNumber={}, pageSize={}, sort={}",
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());

        Page<ParkingSessionDetailResponse> response;
        try {
            LocalDateTime startDateTime = startDate != null && !startDate.isBlank()
                    ? LocalDate.parse(startDate).atStartOfDay()
                    : null;
            LocalDateTime endDateTime = endDate != null && !endDate.isBlank()
                    ? LocalDate.parse(endDate).atTime(LocalTime.MAX)
                    : null;
            response = parkingSessionService.getAllParkingSession(
                    searchKey, parkingLotId, sessionType, typeVehicle,
                    entryLaneId, exitLaneId, statusInOut, startDateTime, endDateTime, pageable);
        } catch (Exception ex) {

            log.error("[getAllParkingSessions] EXCEPTION khi gọi service: {}", ex.getMessage(), ex);
            throw ex;
        }
        log.info("[getAllParkingSessions] RESULT → totalElements={}, totalPages={}, " +
                        "pageNumber={}, numberOfElements={}",
                response.getTotalElements(),
                response.getTotalPages(),
                response.getNumber(),
                response.getNumberOfElements());

        if (response.isEmpty()) {
            log.warn("[getAllParkingSessions] Page rỗng! Kiểm tra DB có data không, " +
                    "hoặc filter đang lọc hết bản ghi.");
        }
        return ResponseEntity.ok(response);
    }

    private Pageable validatePageableSort(Pageable pageable) {
        boolean hasInvalidSort = pageable.getSort().stream()
                .anyMatch(order -> order.getProperty().contains("string"));

        if (hasInvalidSort) {
            return PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "entryTime")
            );
        }
        return pageable;
    }

    private Pageable buildPageable(int page, int size, String sortBy, String sortDirection) {
        String resolvedSortBy = sortBy != null && !sortBy.isBlank() ? sortBy : "entryTime";
        String resolvedSortDirection = sortDirection != null && !sortDirection.isBlank() ? sortDirection : "DESC";

        return PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.fromString(resolvedSortDirection), resolvedSortBy)
        );
    }

    @GetMapping("/parking-lot/{parkingLotId}")
    public ResponseEntity<Page<ParkingSessionDetailResponse>> getParkingSessionsByParkingLot(
            @PathVariable Long parkingLotId,
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "searchKey", required = false) String searchKey,
            @RequestParam(value = "sessionType", required = false) List<String> sessionType,
            @RequestParam(value = "typeVehicle", required = false) String typeVehicle,
            @RequestParam(value = "entryLaneId", required = false) Long entryLaneId,
            @RequestParam(value = "exitLaneId", required = false) Long exitLaneId,
            @RequestParam(value = "statusInOut", required = false) String statusInOut,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sortBy", defaultValue = "entryTime", required = false) String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC", required = false) String sortDirection
    ) {

        Pageable pageable = buildPageable(page, size, sortBy, sortDirection);
        Page<ParkingSessionDetailResponse> response = parkingSessionService.getIdParkingSessionByParkingLot(
                parkingLotId, buildingId, searchKey, sessionType, typeVehicle, entryLaneId, exitLaneId, statusInOut, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/payment-details")
    public ResponseEntity<Page<ParkingSessionPaymentDetailResponse>> getParkingSessionPaymentDetailsByParkingLot(
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "parkingLotId", required = false) Long parkingLotId,
            @RequestParam(value = "searchKey", required = false) String searchKey,
            @RequestParam(value = "sessionType", required = false) List<String> sessionType,
            @RequestParam(value = "typeVehicle", required = false) String typeVehicle,
            @RequestParam(value = "entryLaneId", required = false) Long entryLaneId,
            @RequestParam(value = "exitLaneId", required = false) Long exitLaneId,
            @RequestParam(value = "statusInOut", required = false) String statusInOut,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sortBy", defaultValue = "entryTime", required = false) String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "DESC", required = false) String sortDirection
    ) {

        Pageable pageable = buildPageable(page, size, sortBy, sortDirection);
        LocalDateTime startDateTime = startDate != null && !startDate.isBlank()
                ? LocalDate.parse(startDate).atStartOfDay()
                : null;
        LocalDateTime endDateTime = endDate != null && !endDate.isBlank()
                ? LocalDate.parse(endDate).atTime(LocalTime.MAX)
                : null;
        Page<ParkingSessionPaymentDetailResponse> response = parkingSessionService.getParkingSessionPaymentDetailsByParkingLot(
                buildingId, parkingLotId, searchKey, sessionType, typeVehicle, entryLaneId, exitLaneId, statusInOut,
                startDateTime, endDateTime, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/slot-statistics")
    public ResponseEntity<DashboardStatsResponse> getParkingSessionStatistics(
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "parkingLotId", required = false) Long parkingLotId

    ) {
        log.info("[getParkingSessionStatistics] RAW PARAMS → parkingLotId={}",
                parkingLotId, buildingId);

        try {
            DashboardStatsResponse response = parkingSessionService.getDashboardStatistics(
                    buildingId, parkingLotId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException ex) {
            log.error("[getParkingSessionStatistics] Lỗi tham số đầu vào: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception ex) {
            log.error("[getParkingSessionStatistics] EXCEPTION khi thống kê: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    @GetMapping("/statistic-in-out")
    public ResponseEntity<DashboardStatsResponseInOut> getParkingSessionStatisInOut(
            @RequestParam(value = "buildingId", required = false) Long buildingId,
            @RequestParam(value = "parkingLotId", required = false) Long parkingLotId

    ) {
        try {
            DashboardStatsResponseInOut response = parkingSessionService.getDashboardStatisInOut(
                    buildingId, parkingLotId);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException ex) {
            log.error("[getParkingSessionStatistics] Lỗi tham số đầu vào: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception ex) {
            log.error("[getParkingSessionStatistics] EXCEPTION khi thống kê: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

}
