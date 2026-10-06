package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.VehicleCustomerEntity;

import java.util.List;
import java.util.Optional;

public interface VehicleCustomerRepository extends JpaRepository<VehicleCustomerEntity, Long> {
    boolean existsByVehicleIdAndCustomerId(Long vehicleId, Long customerId);

    Optional<VehicleCustomerEntity> findByVehicleIdAndCustomerId(Long vehicleId, Long customerId);

    Optional<VehicleCustomerEntity> findByIdAndVehicleIdAndCustomerId(Long id, Long vehicleId, Long customerId);

    List<VehicleCustomerEntity> findByVehicleOwnerId(Long ownerId);
}
