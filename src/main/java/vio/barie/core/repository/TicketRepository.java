package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.common.TicketStatus;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<TicketEntity, Long>, JpaSpecificationExecutor<TicketEntity> {
    Optional<TicketEntity> findByVehicleIdAndStatus(Long vehicleId, TicketStatus status);

    Optional<TicketEntity> findById(Long Id);

    Optional<TicketEntity> findTicketByCustomerIdAndVehicleId(Long customerId, Long vehicleId);

    Boolean existsByVehicleId(Long vehicleId);

    Optional<TicketEntity> findTicketByVehicleId(Long vehicleId);
}