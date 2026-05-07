package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.TicketPaymentEntity;
import vio.barie.core.entity.VehicleUserPermissionEntity;

import java.util.Optional;

public interface TicketPaymentRepository extends JpaRepository<TicketPaymentEntity, Long> {
    Boolean existsByPaymentId(Long paymentId);
}