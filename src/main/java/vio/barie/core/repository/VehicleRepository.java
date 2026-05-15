package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.VehicleEntity;

import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    Optional<VehicleEntity> findByPlateNumber(String plateNumber);
    Optional<VehicleEntity> findByAiIdentityPlateId(String aiIdentityPlateId);
    boolean existsByPlateNumber (String plateNumber);
    VehicleEntity findPlateByPlateNumber(String plateNumber);
}