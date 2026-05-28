package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.TicketEntity;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<TicketEntity, Long>, JpaSpecificationExecutor<TicketEntity> {
    Optional<TicketEntity> findByVehicleIdAndStatus(Long vehicleId, String status);

    Optional<TicketEntity> findById(Long Id);

    Optional<TicketEntity> findTicketByCustomerIdAndVehicleId(Long customerId, Long vehicleId);

    Boolean existsByVehicleId(Long vehicleId);

    Optional<TicketEntity> findTicketByVehicleId(Long vehicleId);

    @Query(value = """
            SELECT t FROM TicketEntity t 
            LEFT JOIN FETCH t.vehicle v 
            LEFT JOIN FETCH t.customer c 
            WHERE (:searchKey IS NULL OR :searchKey = '' OR 
                   LOWER(c.username) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR 
                   LOWER(v.plateNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR 
                   LOWER(c.phoneNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')))
            AND (:status IS NULL OR :status = '' OR 
                 (:status = 'ACTIVE' AND CURRENT_TIMESTAMP BETWEEN t.registerAt AND t.expireAt) OR 
                 (:status = 'INACTIVE' AND CURRENT_TIMESTAMP > t.expireAt))
            """,
            countQuery = """
                    SELECT COUNT(t) FROM TicketEntity t 
                    LEFT JOIN t.vehicle v 
                    LEFT JOIN t.customer c 
                    WHERE (:searchKey IS NULL OR :searchKey = '' OR 
                           LOWER(c.username) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR 
                           LOWER(v.plateNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR 
                           LOWER(c.phoneNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')))
                    AND (:status IS NULL OR :status = '' OR 
                         (:status = 'ACTIVE' AND CURRENT_TIMESTAMP BETWEEN t.registerAt AND t.expireAt) OR 
                         (:status = 'INACTIVE' AND CURRENT_TIMESTAMP > t.expireAt))
                    """)
    Page<TicketEntity> searchTickets(
            @Param("searchKey") String searchKey,
            @Param("status") String status,
            Pageable pageable
    );
}