package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.ParkingSessionEntity;

import java.util.Optional;

public interface ParkingSessionRepository extends JpaRepository<ParkingSessionEntity, Long> {
    // session "đang mở" = đã có entry_time, chưa có exit_time
    Optional<ParkingSessionEntity> findFirstByVehicleIdAndExitTimeIsNullOrderByEntryTimeDesc(Long vehicleId);

    Optional<ParkingSessionEntity> findFirstByVehicleIdAndEntryCustomerIdAndExitTimeIsNullOrderByEntryTimeDesc(
            Long vehicleId,
            Long entryCustomerId);
}
