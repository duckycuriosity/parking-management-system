package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.ParkingSessionDetailResponse;
import vio.barie.core.service.ParkingSessionService;

@RestController
@RequestMapping("/api/v1/parking-sessions")
@RequiredArgsConstructor
public class ParkingSessionController {
    private final ParkingSessionService parkingSessionService;

    @GetMapping("/parking-lot/{parkingLotId}")
    public ResponseEntity<Page<ParkingSessionDetailResponse>> getParkingSessionsByParkingLot(
            @PathVariable Long parkingLotId,
            @RequestParam(value = "searchKey", required = false) String searchKey,
            @RequestParam(value = "sessionType", required = false) String sessionType,
            @RequestParam(value = "typeVehicle", required = false) String typeVehicle,
            @RequestParam(value = "entryLaneId", required = false) Long entryLaneId,
            @RequestParam(value = "exitLaneId", required = false) Long exitLaneId,
            @RequestParam(value = "statusInOut", required = false) String statusInOut,
            @PageableDefault(page = 0, size = 10, sort = "entryTime", direction = Sort.Direction.DESC) Pageable pageable
    ) {

        // Phòng trường hợp tham số sort rác
        boolean hasInvalidSort = pageable.getSort().stream()
                .anyMatch(order -> order.getProperty().contains("string"));

        if (hasInvalidSort) {
            pageable = PageRequest.of(
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    Sort.by(Sort.Direction.DESC, "entryTime")
            );
        }


        Page<ParkingSessionDetailResponse> response = parkingSessionService.getAllParkingSessionByParkingLot(
                parkingLotId, searchKey, sessionType, typeVehicle, entryLaneId, exitLaneId, statusInOut, pageable);

        return ResponseEntity.ok(response);
    }

}
