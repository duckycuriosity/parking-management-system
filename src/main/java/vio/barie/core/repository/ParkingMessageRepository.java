package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vio.barie.core.entity.ParkingMessageEntity;

import java.util.List;

@Repository
public interface ParkingMessageRepository extends JpaRepository<ParkingMessageEntity, Long> {
    List<ParkingMessageEntity> findTop50ByDeviceIdOrderByCreatedAtDesc(String deviceId);

    List<ParkingMessageEntity> findTop100ByOrderByCreatedAtDesc();
}


