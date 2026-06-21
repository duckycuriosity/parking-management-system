package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.dto.parking.VehicleMetricsResponse;
import vio.barie.core.entity.ParkingSessionEntity;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ParkingSessionRepository extends JpaRepository<ParkingSessionEntity, Long> {
        // session "đang mở" = đã có entry_time, chưa có exit_time

        boolean existsByParkingLot_Building_Id(Long buildingId);

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
                        "AND (:searchKey IS NULL OR :searchKey = '' OR(" +
                        "     LOWER(ec.username) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR " +
                        "     LOWER(v.plateNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')))) " +
                        "AND (:sessionType IS NULL OR s.sessionType IN :sessionType) " +
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
                        @Param("sessionType") List<SessionType> sessionType,
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
                        "AND (:sessionType IS NULL OR s.sessionType IN :sessionType) " +
                        "AND (:typeVehicle IS NULL OR v.vehicleType = :typeVehicle) " +
                        "AND (:entryLaneId IS NULL OR el.id = :entryLaneId) " +
                        "AND (:exitLaneId IS NULL OR xl.id = :exitLaneId) " +
                        "AND (:statusInOut IS NULL OR :statusInOut = '' " +
                        "     OR (:statusInOut = 'IN' AND s.exitTime IS NULL) " +
                        "     OR (:statusInOut = 'OUT' AND s.exitTime IS NOT NULL)) " +
                        "ORDER BY s.entryTime DESC")
        Page<ParkingSessionEntity> findAllParkingLotWithFilters(
                        @Param("searchKey") String searchKey,
                        @Param("sessionType") List<SessionType> sessionType,
                        @Param("typeVehicle") String typeVehicle,
                        @Param("entryLaneId") Long entryLaneId,
                        @Param("exitLaneId") Long exitLaneId,
                        @Param("statusInOut") String statusInOut,
                        Pageable pageable);

        @Query(value = "SELECT COUNT(*) FROM vehicle v " +
                        "WHERE v.vehicle_type = :vehicle_type " +
                        "AND v.id IN ( " +
                        "    SELECT ps.vehicle_id FROM parking_session ps " +
                        "    WHERE ps.exit_time IS NULL " +
                        "    AND (:building_id IS NULL OR ps.parking_lot_id IN ( " +
                        "        SELECT pl.id FROM parking_lot pl WHERE pl.building_id = :building_id" +
                        "    )) " +
                        ")", nativeQuery = true)
        Integer countNumberOfVehicleInParkingLot(@Param("building_id") Integer building_id,
                        @Param("vehicle_type") String vehicle_type);

        @Query(value = """
            SELECT
                TO_CHAR(DATE_TRUNC('hour', entry_time), 'YYYY-MM-DD HH24:00:00') as hour,
                COUNT(*) as entries
            FROM parking_session
            WHERE (:buildingId IS NULL OR parking_lot_id IN (SELECT pl.id FROM parking_lot pl WHERE pl.building_id = :buildingId))
            GROUP BY DATE_TRUNC('hour', entry_time)
            ORDER BY hour
            """, nativeQuery = true)
        List<Object[]> countEntriesByHour(@Param("buildingId") Integer buildingId,
                                          @Param("startDate") LocalDateTime startDate,
                                          @Param("endDate") LocalDateTime endDate);

        @Query(value = """
            SELECT
                TO_CHAR(DATE_TRUNC('hour', exit_time), 'YYYY-MM-DD HH24:00:00') as hour,
                COUNT(*) as exits
            FROM parking_session
            WHERE (:buildingId IS NULL OR parking_lot_id IN (SELECT pl.id FROM parking_lot pl WHERE pl.building_id = :buildingId))
                AND exit_time IS NOT NULL
            GROUP BY DATE_TRUNC('hour', exit_time)
            ORDER BY hour
            """, nativeQuery = true)
        List<Object[]> countExitsByHour(@Param("buildingId") Integer buildingId,
                                        @Param("startDate") LocalDateTime startDate,
                                        @Param("endDate") LocalDateTime endDate);
}
