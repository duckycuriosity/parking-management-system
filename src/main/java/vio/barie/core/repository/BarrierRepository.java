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
    long countByParkingDevice_Status(OperatingStatus status);

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
