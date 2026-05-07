package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.PaymentEntity;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findById(Long Id);
    Optional<PaymentEntity> findByInvoiceNo(String invoiceNo);
    boolean existsById(Long Id);
}
