package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.VehicleEntity;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VehicleEntity v where v.id = :id and v.ownerId = :ownerId")
    Optional<VehicleEntity> findOwnedForUpdate(@Param("id") Long id, @Param("ownerId") Long ownerId);
    Optional<VehicleEntity> findByPlateNumber(String plateNumber);

    Optional<VehicleEntity> findByAiIdentityPlateId(String aiIdentityPlateId);

    boolean existsByPlateNumber(String plateNumber);

    boolean existsByPlateNumberAndIdNot(String plateNumber, Long id);

    long countByOwnerId(Long ownerId);

    Optional<VehicleEntity> findByIdAndOwnerId(Long id, Long ownerId);

    VehicleEntity findPlateByPlateNumber(String plateNumber);

    VehicleEntity findFirstByPlateNumberOrderByIdDesc(String plateNumber);

    Optional<VehicleEntity> findVehicleById(Long id);

    @Query("""
            SELECT
                v.id AS vehicleId,
                v.plateNumber AS plate,
                v.vehicleType AS vehicleType,
                v.status AS vehicleStatus
            FROM VehicleEntity v
            WHERE v.ownerId = :ownerId
            ORDER BY v.createdAt DESC, v.id DESC
            """)
    List<RegisteredVehicleProjection> findRegisteredVehiclesByOwnerId(@Param("ownerId") Long ownerId);

    interface RegisteredVehicleProjection {
        Long getVehicleId();

        String getPlate();

        String getVehicleType();

        String getVehicleStatus();
    }
}
