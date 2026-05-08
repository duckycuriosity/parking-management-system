package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import vio.barie.core.entity.TicketEntity;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<TicketEntity, Long>, JpaSpecificationExecutor<TicketEntity> {
    Optional<TicketEntity> findByVehicleIdAndStatus (Long vehicleId,String status);
    Optional<TicketEntity> findById(Long Id);
}