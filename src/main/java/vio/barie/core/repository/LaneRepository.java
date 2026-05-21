package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.entity.SysUserEntity;

import java.util.Optional;

public interface LaneRepository extends JpaRepository<LaneEntity, Long> {
    @Query("SELECT l FROM LaneEntity l JOIN FETCH l.parkingLot WHERE l.id = :id")
    Optional<LaneEntity> findByIdWithParkingLot(@Param("id") Long id);

}