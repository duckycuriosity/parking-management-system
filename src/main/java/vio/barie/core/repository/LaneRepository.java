package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.LaneActivityDTO;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.entity.SysUserEntity;
import vio.barie.core.utils.DirectionType;

import java.util.List;
import java.util.Optional;

public interface LaneRepository extends JpaRepository<LaneEntity, Long> {
    @Query("SELECT l FROM LaneEntity l JOIN FETCH l.parkingLot WHERE l.id = :id")
    Optional<LaneEntity> findByIdWithParkingLot(@Param("id") Long id);

    @Query("SELECT l FROM LaneEntity l " +
            "JOIN FETCH l.parkingLot p " +
            "WHERE p.id = :parkingLotId " +
            "AND (:type IS NULL OR l.type = :type)" +
            "AND (:status IS NULL OR l.status = :status)"
    )
    List<LaneEntity> findAllByParkingLotIdAndTypeAndStatus(
            @Param("parkingLotId") Long parkingLotId,
            @Param("type") DirectionType type,
            @Param("status") BaseStatus status);

    @Query(value = "SELECT l FROM LaneEntity l JOIN FETCH l.parkingLot",
            countQuery = "SELECT count(l) FROM LaneEntity l")
    Page<LaneEntity> findAllWithParkingLot(Pageable pageable);


    boolean existsByParkingLot_Building_Id(Long buildingId);

    @Query(value = """
    WITH latest_activity AS (
        SELECT 
            lane_id,
            action_type,
            action_time,
            parking_lot_id,
            vehicle_id,
            ROW_NUMBER() OVER (PARTITION BY lane_id ORDER BY action_time DESC) AS rn
        FROM (
            SELECT 
                entry_lane_id AS lane_id,
                'ENTRY' AS action_type,
                entry_time AS action_time,
                parking_lot_id,
                vehicle_id
            FROM parking_session
            WHERE entry_lane_id IS NOT NULL
            
            UNION ALL
            
            SELECT 
                exit_lane_id AS lane_id,
                'EXIT' AS action_type,
                exit_time AS action_time,
                parking_lot_id,
                vehicle_id
            FROM parking_session
            WHERE exit_lane_id IS NOT NULL
        ) t
    )
    SELECT 
        la.lane_id AS laneId,
        l.name AS laneName,
        l.type AS laneType,
        la.action_type AS lastAction,
        la.action_time AS lastActionTime,
        la.parking_lot_id AS parkingLotId,
        la.vehicle_id AS vehicleId,
        v.plate_number AS licensePlate,
        v.vehicle_type AS vehicleType
    FROM latest_activity la
    JOIN lane l ON la.lane_id = l.id
    LEFT JOIN vehicle v ON la.vehicle_id = v.id
    WHERE la.rn = 1
    ORDER BY la.lane_id
    """, nativeQuery = true)
    List<LaneActivityDTO> findLatestActivityPerLane();
}