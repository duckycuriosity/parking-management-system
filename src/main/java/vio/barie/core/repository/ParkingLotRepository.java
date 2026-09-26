package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.parking.ParkingLotCapacityProjection;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.SysUserEntity;

import java.util.List;

public interface ParkingLotRepository extends JpaRepository<ParkingLotEntity, Long> {
    @Query("SELECT COALESCE(SUM(p.maxMotorcycleCapacity), 0), COALESCE(SUM(p.maxCarCapacity), 0) " +
            "FROM ParkingLotEntity p " +
            "WHERE (:parkingLotId IS NULL OR p.id = :parkingLotId)" +
            "AND (:buildingId IS NULL OR p.building.id = :buildingId)")
    List<Object[]> getCapacityStats(@Param("buildingId") Long buildingId, @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT p.name FROM ParkingLotEntity p WHERE p.building.id = :buildingId AND (:excludeId IS NULL OR p.id != :excludeId)")
    List<String> findAllNamesByBuildingExcludingId(@Param("buildingId") Long buildingId, @Param("excludeId") Long excludeId);

    List<ParkingLotEntity> findByBuildingId(Long buildingId);

    List<ParkingLotEntity> findByStatus(BaseStatus status);

    boolean existsByBuildingId(Long buildingId);

    List<ParkingLotEntity> findByBuildingIdAndStatus(Long buildingId, BaseStatus status);

    @Query(value = """
            SELECT
                pl.id AS parkingLotId,
                pl.name AS parkingLotName,
                COALESCE(pl.max_motorcycle_capacity, 0) AS motorbikeCapacity,
                COALESCE(pl.max_car_capacity, 0) AS carCapacity,
                COUNT(ps.id) AS usedTotal,
                COUNT(CASE WHEN v.vehicle_type = 'MOTORBIKE' THEN 1 END) AS usedMotorbike,
                COUNT(CASE WHEN v.vehicle_type = 'CAR' THEN 1 END) AS usedCar,
                COUNT(CASE WHEN v.vehicle_type IS NOT NULL
                            AND v.vehicle_type NOT IN ('MOTORBIKE', 'CAR')
                           THEN 1 END) AS usedOther
            FROM parking_lot pl
            LEFT JOIN parking_session ps
                ON ps.parking_lot_id = pl.id
               AND ps.exit_time IS NULL
            LEFT JOIN vehicle v
                ON v.id = ps.vehicle_id
            WHERE (:buildingId IS NULL OR pl.building_id = :buildingId)
              AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
              AND pl.status = 'ACTIVE'
            GROUP BY pl.id, pl.name, pl.max_motorcycle_capacity, pl.max_car_capacity
            ORDER BY pl.id
            """, nativeQuery = true)
    List<ParkingLotCapacityProjection> getCapacityByLot(@Param("buildingId") Long buildingId,
                                                        @Param("parkingLotId") Long parkingLotId);
}
