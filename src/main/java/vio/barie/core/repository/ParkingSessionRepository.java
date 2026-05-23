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


    Optional<ParkingSessionEntity> findFirstByVehicleIdAndEntryCustomerIdAndExitTimeIsNullOrderByEntryTimeDesc(
            Long vehicleId,
            Long entryCustomerId);

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

    @Query("SELECT new vio.barie.core.dto.ActivitySessionResponse(" +
            "s.id, v.plateNumber, v.vehicleType, " +
            "s.entryTime, s.exitTime, " +
            "p.name, el.name, xl.name, null) " +
            "FROM ParkingSessionEntity s " +
            "LEFT JOIN s.vehicle v " +
            "LEFT JOIN s.parkingLot p " +
            "LEFT JOIN s.entryLane el " +
            "LEFT JOIN s.exitLane xl " +
            "ORDER BY COALESCE(s.exitTime, s.entryTime) DESC")
    Page<ActivitySessionResponse> getAllActivityHistory(Pageable pageable);


    @Query("SELECT s, t.status FROM ParkingSessionEntity s " +
            "LEFT JOIN s.vehicle v " +
            "LEFT JOIN s.entryLane el " +
            "LEFT JOIN s.exitLane xl " +
            "LEFT JOIN TicketEntity t ON s.ticketId = t.id " +
            "LEFT JOIN ParkingCustomerEntity c ON t.customerId = c.id " +
            "WHERE s.parkingLot.id = :parkingLotId " +
            "AND (:username = '' OR LOWER(c.username) LIKE LOWER(CONCAT('%', :username, '%'))) " +
            "AND (:sessionType = '' OR s.sessionType = :sessionType) " +
            "AND (:typeVehicle = '' OR UPPER(v.vehicleType) = UPPER(:typeVehicle)) " +
            "AND (:entryLaneId IS NULL OR el.id = :entryLaneId) " +
            "AND (:exitLaneId IS NULL OR xl.id = :exitLaneId) " +
            "AND (:status = '' OR UPPER(t.status) = :status) " +
            "ORDER BY s.entryTime DESC")
    Page<Object[]> findAllByParkingLotWithFilters(
            @Param("parkingLotId") Long parkingLotId,
            @Param("username") String userName,
            @Param("sessionType") String sessionType,
            @Param("typeVehicle") String typeVehicle,
            @Param("entryLaneId") Long entryLaneId,
            @Param("exitLaneId") Long exitLaneId,
            @Param("status") String status,
            Pageable pageable);
}
