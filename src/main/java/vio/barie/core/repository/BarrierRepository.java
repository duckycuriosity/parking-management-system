package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.entity.BarrierEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface BarrierRepository extends JpaRepository<BarrierEntity, Long> {

    @Query("SELECT COUNT(b) FROM BarrierEntity b " +
            "LEFT JOIN b.parkingDevice pd LEFT JOIN pd.lane l LEFT JOIN l.parkingLot p " +
            "WHERE (:parkingLotId IS NULL OR p.id = :parkingLotId) " +
            "AND (:buildingId IS NULL OR p.building.id  = :buildingId)"
    )
    long countBarriersWithFilters(@Param("buildingId") Long buildingId, @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT COUNT(b) FROM BarrierEntity b " +
            "LEFT JOIN b.parkingDevice pd LEFT JOIN pd.lane l LEFT JOIN l.parkingLot p " +
            "WHERE pd.status = :status " +
            "AND (:parkingLotId IS NULL OR p.id = :parkingLotId) " +
            "AND (:buildingId IS NULL OR p.building.id = :buildingId)")
    long countBarriersByDeviceStatusWithFilters(
            @Param("status") OperatingStatus status,
            @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId);

    Optional<BarrierEntity> findByBarrierId(String barrierId);

    @Query("select b from BarrierEntity b join fetch b.parkingDevice where b.barrierId = :barrierId")
    Optional<BarrierEntity> findByBarrierIdWithDevice(@Param("barrierId") String barrierId);

    @Query("select b from BarrierEntity b " +
            "join fetch b.parkingDevice pd " +
            "join fetch pd.lane l " +
            "join fetch l.parkingLot " +
            "where b.barrierId = :barrierId")
    Optional<BarrierEntity> findByBarrierIdWithDeviceAndLaneAndParkingLot(@Param("barrierId") String barrierId);

    List<BarrierEntity> findAllByParkingDevice_Id(Long parkingDeviceId);

    List<BarrierEntity> findAllByParkingDevice_DeviceId(String deviceId);

    @Query("select b from BarrierEntity b " +
            "join fetch b.parkingDevice pd " +
            "join fetch pd.lane l " +
            "join fetch l.parkingLot pl " +
            "where b.barrierId in (select bc.barrierId from BarrierCameraEntity bc " +
            "where bc.cameraId = :cameraId and lower(bc.role) = lower(:role))")
    Optional<BarrierEntity> findBarrierByCameraIdAndRole(@Param("cameraId") Long cameraId,
                                                         @Param("role") String role);

    @Query("SELECT b FROM BarrierEntity b " +
            "JOIN FETCH b.parkingDevice pd " +
            "LEFT JOIN FETCH pd.lane")
    List<BarrierEntity> findAllWithDeviceAndLane();


}
