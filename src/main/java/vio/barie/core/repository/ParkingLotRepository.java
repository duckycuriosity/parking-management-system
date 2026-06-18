package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.SysUserEntity;

import java.util.List;

public interface ParkingLotRepository extends JpaRepository<ParkingLotEntity, Long> {
    @Query("SELECT COALESCE(SUM(p.maxMotorcycleCapacity), 0), COALESCE(SUM(p.maxCarCapacity), 0) " +
            "FROM ParkingLotEntity p " +
            "WHERE (:parkingLotId IS NULL OR p.id = :parkingLotId)")
    List<Object[]> getCapacityStats(@Param("parkingLotId") Long parkingLotId);

    @Query("SELECT p.name FROM ParkingLotEntity p WHERE p.building.id = :buildingId AND (:excludeId IS NULL OR p.id != :excludeId)")
    List<String> findAllNamesByBuildingExcludingId(@Param("buildingId") Long buildingId, @Param("excludeId") Long excludeId);

    List<ParkingLotEntity> findByBuildingId(Long buildingId);

    List<ParkingLotEntity> findByStatus(BaseStatus status);

    boolean existsByBuildingId(Long buildingId);

    List<ParkingLotEntity> findByBuildingIdAndStatus(Long buildingId, BaseStatus status);
}