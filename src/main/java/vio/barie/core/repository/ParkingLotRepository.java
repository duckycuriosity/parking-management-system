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
    List<ParkingLotEntity> findByBuildingId(Long buildingId);

    List<ParkingLotEntity> findByStatus(BaseStatus status);

    boolean existsByBuildingId(Long buildingId);

    List<ParkingLotEntity> findByBuildingIdAndStatus(Long buildingId, BaseStatus status);
}