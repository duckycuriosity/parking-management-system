package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.VehicleUserPermissionEntity;

import java.util.List;
import java.util.Optional;

public interface VehicleUserPermissionRepository extends JpaRepository<VehicleUserPermissionEntity, Long> {
    boolean existsByParkingCustomerIdAndVehicleId(Long customerId, Long vehicleId);
    @Query("SELECT p FROM VehicleUserPermissionEntity p JOIN FETCH p.vehicle WHERE p.parkingCustomer.id = :customerId")
    List<VehicleUserPermissionEntity> findByParkingCustomerIdWithVehicle(@Param("customerId") Long customerId);
}