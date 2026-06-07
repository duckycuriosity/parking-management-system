package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.BaseStatus;
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

}