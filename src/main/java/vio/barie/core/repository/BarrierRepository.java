package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vio.barie.core.entity.BarrierEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface BarrierRepository extends JpaRepository<BarrierEntity, Long> {
    Optional<BarrierEntity> findByBarrierId(String barrierId);

    List<BarrierEntity> findAllByParkingDevice_Id(Long parkingDeviceId);

    List<BarrierEntity> findAllByParkingDevice_DeviceId(String deviceId);
}
