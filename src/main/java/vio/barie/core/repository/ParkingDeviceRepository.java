package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vio.barie.core.entity.ParkingDeviceEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ParkingDeviceRepository extends JpaRepository<ParkingDeviceEntity, Long> {
    Optional<ParkingDeviceEntity> findByDeviceId(String deviceId);

    List<ParkingDeviceEntity> findByLane_Id(Long laneId);

    List<ParkingDeviceEntity> findByStatus(String status);

    List<ParkingDeviceEntity> findByLastSeenBeforeAndStatus(
            LocalDateTime time,
            String status);
}
