package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.entity.ParkingSessionEntity;
import org.springframework.data.domain.Pageable;

import java.util.Optional;


public interface ParkingSessionRepository extends JpaRepository<ParkingSessionEntity, Long> {
    // session "đang mở" = đã có entry_time, chưa có exit_time
    Optional<ParkingSessionEntity> findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(Long vehicleId);

    @Query("SELECT new vio.barie.core.dto.ActivitySessionResponse(" +
            "s.id, v.plateNumber, v.vehicleType, " +
            "s.entryTime, s.exitTime, " +
            "p.name, el.name, xl.name, null) " +
            "FROM ParkingSessionEntity s " +
            "LEFT JOIN s.vehicle v " +
            "LEFT JOIN s.parkingLot p " +
            "LEFT JOIN s.entryLane el " +
            "LEFT JOIN s.exitLane xl " +
            "WHERE (el.id = :laneId OR xl.id = :laneId) " +
            "ORDER BY COALESCE(s.exitTime, s.entryTime) DESC")
    Page<ActivitySessionResponse> getActivityHistoryByLaneId(@Param("laneId") Long laneId, Pageable pageable);


}
