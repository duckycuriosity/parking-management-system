package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.SysUserEntity;
import vio.barie.core.entity.VehicleUserPermissionEntity;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findById(Long Id);
    boolean existsById(Long Id);
}