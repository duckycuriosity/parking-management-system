package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.SessionType;
import vio.barie.core.dto.ActivitySessionResponse;
import vio.barie.core.dto.SessionStatProjection;
import vio.barie.core.dto.VehicleStatProjection;
import vio.barie.core.dto.LaneActivityDTO;
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
//            "LEFT JOIN TicketEntity t ON s.ticketId = t.id " +
//            "LEFT JOIN t.customer c " +
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
//            "LEFT JOIN TicketEntity t ON s.ticketId = t.id " +
//            "LEFT JOIN t.customer c " +
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


    @Query("SELECT COUNT(s.id) FROM ParkingSessionEntity s " +
            "WHERE s.entryTime >= :startDate AND s.entryTime <= :endDate " +
            "AND (:parkingLotId IS NULL OR s.parkingLot.id = :parkingLotId)")
    long countTotalEntries(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT COUNT(s.id) FROM ParkingSessionEntity s " +
            "WHERE s.exitTime >= :startDate AND s.exitTime <= :endDate " +
            "AND (:parkingLotId IS NULL OR s.parkingLot.id = :parkingLotId)")
    long countTotalExits(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT v.vehicleType AS vehicleType, COUNT(s.id) AS total " +
            "FROM ParkingSessionEntity s " +
            "JOIN s.vehicle v " +
            "WHERE s.entryTime >= :startDate AND s.entryTime <= :endDate " +
            "AND (:parkingLotId IS NULL OR s.parkingLot.id = :parkingLotId) " +
            "GROUP BY v.vehicleType")
    List<VehicleStatProjection> countVehicleTypeStats(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT COUNT(s.id) FROM ParkingSessionEntity s " +
            "WHERE s.sessionType IS NOT NULL " +
            "AND s.entryTime >= :startDate AND s.entryTime <= :endDate " +
            "AND (:parkingLotId IS NULL OR s.parkingLot.id = :parkingLotId)")
    long countTotalSessionTypes(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT s.sessionType AS sessionType, COUNT(s.id) AS total " +
            "FROM ParkingSessionEntity s " +
            "WHERE s.sessionType IS NOT NULL " +
            "AND s.entryTime >= :startDate AND s.entryTime <= :endDate " +
            "AND (:parkingLotId IS NULL OR s.parkingLot.id = :parkingLotId) " +
            "GROUP BY s.sessionType")
    List<SessionStatProjection> countSessionTypeDetails(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("parkingLotId") Long parkingLotId);

    @Query(value = """
            SELECT 
                TO_CHAR(DATE_TRUNC('hour', entry_time), 'YYYY-MM-DD HH24:00:00') as hour,
                COUNT(*) as entries
            FROM parking_session
            WHERE parking_lot_id = :parkingLotId
                AND entry_time BETWEEN :startDate AND :endDate
            GROUP BY DATE_TRUNC('hour', entry_time)
            ORDER BY hour
            """,
            nativeQuery = true)
    List<Object[]> countEntriesByHour(@Param("parkingLotId") Integer parkingLotId,
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
