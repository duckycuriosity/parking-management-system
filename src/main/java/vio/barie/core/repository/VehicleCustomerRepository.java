package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.VehicleCustomerEntity;

import java.util.List;
import java.util.Optional;

public interface VehicleCustomerRepository extends JpaRepository<VehicleCustomerEntity, Long> {
    boolean existsByVehicleIdAndCustomerId(Long vehicleId, Long customerId);

    Optional<VehicleCustomerEntity> findByVehicleIdAndCustomerId(Long vehicleId, Long customerId);

    Optional<VehicleCustomerEntity> findByIdAndVehicleIdAndCustomerId(Long id, Long vehicleId, Long customerId);

    @Query(value = """
            SELECT vc.*
            FROM vehicle_customer vc
            INNER JOIN vehicle v ON v.id = vc.vehicle_id
            WHERE v.owner_id = :ownerId
            """, nativeQuery = true)
    List<VehicleCustomerEntity> findByOwnerId(@Param("ownerId") Long ownerId);
}
