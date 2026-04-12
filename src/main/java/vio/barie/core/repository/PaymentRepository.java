package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.SysUserEntity;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
}