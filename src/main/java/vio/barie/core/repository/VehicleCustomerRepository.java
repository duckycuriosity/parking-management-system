package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.VehicleCustomerEntity;

import java.util.List;
import java.util.Optional;

public interface VehicleCustomerRepository extends JpaRepository<VehicleCustomerEntity, Long> {
    boolean existsByVehicleIdAndStatus(Long vehicleId, String status);

    @Query("select (count(r) > 0) from VehicleCustomerEntity r join ParkingCustomerEntity c on c.id = r.customerId where r.vehicleId = :vehicleId and r.status = 'ACTIVE' and (c.status is null or c.status <> 'INACTIVE')")
    boolean hasActiveUser(@Param("vehicleId") Long vehicleId);
    boolean existsByVehicleIdAndCustomerId(Long vehicleId, Long customerId);

    Optional<VehicleCustomerEntity> findByVehicleIdAndCustomerId(Long vehicleId, Long customerId);

    Optional<VehicleCustomerEntity> findByIdAndVehicleIdAndCustomerId(Long id, Long vehicleId, Long customerId);

    @Query("""
            SELECT vc
            FROM VehicleCustomerEntity vc
            WHERE vc.vehicleId IN (
                SELECT v.id
                FROM VehicleEntity v
                WHERE v.ownerId = :ownerId
            )
            """)
    List<VehicleCustomerEntity> findByOwnerId(@Param("ownerId") Long ownerId);

    @Query("""
            SELECT
                vc.vehicleId AS vehicleId,
                pc.username AS userName
            FROM VehicleCustomerEntity vc
            INNER JOIN ParkingCustomerEntity pc ON pc.id = vc.customerId
            WHERE vc.vehicleId IN :vehicleIds
              AND pc.username IS NOT NULL
            ORDER BY vc.vehicleId ASC, pc.username ASC
            """)
    List<VehicleUserNameProjection> findUserNamesByVehicleIds(@Param("vehicleIds") List<Long> vehicleIds);

    @Query("""
            SELECT
                vc.vehicleId AS vehicleId,
                pc.username AS userName
            FROM VehicleCustomerEntity vc
            INNER JOIN ParkingCustomerEntity pc ON pc.id = vc.customerId
            WHERE vc.vehicleId IN :vehicleIds
              AND vc.status = 'ACTIVE'
              AND (pc.status IS NULL OR pc.status <> 'INACTIVE')
              AND pc.username IS NOT NULL
            ORDER BY vc.vehicleId ASC, pc.username ASC
            """)
    List<VehicleUserNameProjection> findActiveUserNamesByVehicleIds(@Param("vehicleIds") List<Long> vehicleIds);

    interface VehicleUserNameProjection {
        Long getVehicleId();

        String getUserName();
    }
}
