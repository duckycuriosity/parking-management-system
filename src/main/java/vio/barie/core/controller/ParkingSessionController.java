package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.ParkingSessionDetailResponse;
import vio.barie.core.service.ParkingSessionService;

@RestController
@RequestMapping("/api/v1/parking-sessions")
@RequiredArgsConstructor
public class ParkingSessionController {
    private final ParkingSessionService parkingSessionService;

    @GetMapping("/management")
    public ResponseEntity<Page<ParkingSessionDetailResponse>> getParkingSessions(
            @RequestParam(value = "activeOnly", required = false) Boolean activeOnly,
            @RequestParam(value = "search", required = false) String search,          // Ô search username
            @RequestParam(value = "sessionType", required = false) String sessionType,  // Filter: DAY, MONTH, QUARTER, YEAR
            @RequestParam(value = "typeVehicle", required = false) String typeVehicle,  // Filter: Motorbike, Car, Other
            @RequestParam(value = "entryLaneId", required = false) Long entryLaneId, // cả hai th dưới này nữa
            @RequestParam(value = "exitLaneId", required = false) Long exitLaneId,
            @PageableDefault(page = 0, size = 10, sort = "entryTime", direction = Sort.Direction.DESC) Pageable pageable
    ) {

        boolean hasInvalidSort = pageable.getSort().stream()
                .anyMatch(order -> order.getProperty().contains("string"));

        if (hasInvalidSort) {
            pageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "entryTime")
            );
        }

        Page<ParkingSessionDetailResponse> response = parkingSessionService.getParkingSessionsForManagement(
                activeOnly, search, sessionType, typeVehicle, entryLaneId, exitLaneId, pageable);

        return ResponseEntity.ok(response);
    }

}
