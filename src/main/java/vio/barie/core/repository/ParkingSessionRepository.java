package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.TicketType;
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


    @Query("SELECT s FROM ParkingSessionEntity s " +
            "LEFT JOIN s.vehicle v " +
            "LEFT JOIN s.entryLane el " +
            "LEFT JOIN s.exitLane xl " +
            "LEFT JOIN s.entryCustomer ec " +
            "LEFT JOIN TicketEntity t ON s.ticketId = t.id " +
            "LEFT JOIN t.customer c " +
            "WHERE s.parkingLot.id = :parkingLotId " +
            "AND (:searchKey IS NULL OR :searchKey = '' OR " +
            "     LOWER(ec.username) LIKE CONCAT('%', :searchKey, '%') OR " +
            "     LOWER(v.plateNumber) LIKE CONCAT('%', :searchKey, '%')) " +
            "AND (:sessionType IS NULL OR s.sessionType = :sessionType) " +
            "AND (:typeVehicle IS NULL OR v.vehicleType = :typeVehicle) " +
            "AND (:entryLaneId IS NULL OR el.id = :entryLaneId) " +
            "AND (:exitLaneId IS NULL OR xl.id = :exitLaneId) " +
            "AND (:statusInOut IS NULL OR :statusInOut = '' " +
            "     OR (:statusInOut = 'IN' AND s.exitTime IS NULL) " +
            "     OR (:statusInOut = 'OUT' AND s.exitTime IS NOT NULL)) " +
            "ORDER BY s.entryTime DESC")
    Page<ParkingSessionEntity> findIdByParkingLotWithFilters(
            @Param("parkingLotId") Long parkingLotId,
            @Param("searchKey") String searchKey,
            @Param("sessionType") TicketType sessionType,
            @Param("typeVehicle") String typeVehicle,
            @Param("entryLaneId") Long entryLaneId,
            @Param("exitLaneId") Long exitLaneId,
            @Param("statusInOut") String statusInOut,
            Pageable pageable);

    @Query("SELECT s FROM ParkingSessionEntity s " +
            "LEFT JOIN s.vehicle v " +
            "LEFT JOIN s.entryLane el " +
            "LEFT JOIN s.exitLane xl " +
            "LEFT JOIN s.entryCustomer ec " +
            "LEFT JOIN TicketEntity t ON s.ticketId = t.id " +
            "LEFT JOIN t.customer c " +
            "WHERE " +
            "(:searchKey IS NULL OR :searchKey = '' OR (" +
            "     LOWER(ec.username) LIKE LOWER(CONCAT('%', :searchKey, '%')) " +
            "     OR LOWER(v.plateNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')))) " +
            "AND (:sessionType IS NULL OR s.sessionType = :sessionType) " +
            "AND (:typeVehicle IS NULL OR v.vehicleType = :typeVehicle) " +
            "AND (:entryLaneId IS NULL OR el.id = :entryLaneId) " +
            "AND (:exitLaneId IS NULL OR xl.id = :exitLaneId) " +
            "AND (:statusInOut IS NULL OR :statusInOut = '' " +
            "     OR (:statusInOut = 'IN' AND s.exitTime IS NULL) " +
            "     OR (:statusInOut = 'OUT' AND s.exitTime IS NOT NULL)) " +
            "ORDER BY s.entryTime DESC")
    Page<ParkingSessionEntity> findAllParkingLotWithFilters(
            @Param("searchKey") String searchKey,
            @Param("sessionType") TicketType sessionType,
            @Param("typeVehicle") String typeVehicle,
            @Param("entryLaneId") Long entryLaneId,
            @Param("exitLaneId") Long exitLaneId,
            @Param("statusInOut") String statusInOut,
            Pageable pageable);

}
