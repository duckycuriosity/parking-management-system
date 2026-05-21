package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.PaymentEntity;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findById(Long id);
    Optional<PaymentEntity> findByInvoiceNo(String invoiceNo);
    Optional<PaymentEntity> findByInternalRefId(String internalRefId);
    Optional<PaymentEntity> findByGatewayTrxId(String gatewayTrxId);
    boolean existsById(Long id);
    boolean existsByInternalRefId(String internalRefId);
}
