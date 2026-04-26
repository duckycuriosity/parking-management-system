package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.VehicleUserPermissionEntity;

import java.util.Optional;

public interface VehicleUserPermissionRepository extends JpaRepository<VehicleUserPermissionEntity, Long> {
    boolean existsByParkingCustomerIdAndVehicleId(Long customerId, Long vehicleId);
}