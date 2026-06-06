package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.dto.ticket.TicketRegistrationResponseDTO;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<TicketEntity, Long>, JpaSpecificationExecutor<TicketEntity> {
    Optional<TicketEntity> findByVehicleIdAndStatus(Long vehicleId, TicketStatus status);

    Optional<TicketEntity> findById(Long Id);

    Optional<TicketEntity> findTicketByCustomerIdAndVehicleId(Long customerId, Long vehicleId);

    Boolean existsByVehicleId(Long vehicleId);

    Optional<TicketEntity> findTicketByVehicleId(Long vehicleId);

    @Query(value = """
                SELECT new vio.barie.core.dto.ticket.TicketRegistrationResponseDTO(
                    t.id,
                    CONCAT(t.ticketType, ''),
                    CONCAT(t.status, ''),
                    c.id,
                    c.username,
                    v.id,
                    v.plateNumber,
                    v.vehicleType,
                    c.aiProfileId,
                    c.imageUrl,
                    t.registerAt,
                    t.expireAt,
                    t.startDate,
                    null,
                    t.notes,
                    c.email,
                    t.phoneNumber
                )
            FROM TicketEntity t
            LEFT JOIN t.vehicle v
            LEFT JOIN t.customer c
            WHERE (:searchKey IS NULL OR :searchKey = '' OR
                  LOWER(c.username) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR
                  LOWER(v.plateNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR
                  LOWER(t.phoneNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')))
            AND (:status IS NULL OR :status = '' OR
                (:status = 'ACTIVE' AND CURRENT_TIMESTAMP BETWEEN t.registerAt AND t.expireAt) OR
                (:status = 'INACTIVE' AND CURRENT_TIMESTAMP > t.expireAt))
            """, countQuery = """
            SELECT COUNT(t) FROM TicketEntity t
            LEFT JOIN t.vehicle v
            LEFT JOIN t.customer c
            WHERE (:searchKey IS NULL OR :searchKey = '' OR
                  LOWER(c.username) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR
                  LOWER(v.plateNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')) OR
                  LOWER(t.phoneNumber) LIKE LOWER(CONCAT('%', :searchKey, '%')))
            AND (:status IS NULL OR :status = '' OR
                (:status = 'ACTIVE' AND CURRENT_TIMESTAMP BETWEEN t.registerAt AND t.expireAt) OR
                (:status = 'INACTIVE' AND CURRENT_TIMESTAMP > t.expireAt))
            """)
    Page<TicketRegistrationResponseDTO> searchTickets(
            @Param("searchKey") String searchKey,
            @Param("status") String status,
            Pageable pageable);

    // 1. tổng số vé (Bỏ qua DEAD)
    @Query("SELECT COUNT(t) FROM TicketEntity t WHERE t.status IN ('ACTIVE', 'INACTIVE')")
    long countTotalTickets();

    // 2. vé đang hoạt động (status = ACTIVE và chưa qua ngày hết hạn)
    @Query("SELECT COUNT(t) FROM TicketEntity t WHERE t.status = 'ACTIVE' AND t.expireAt >= CURRENT_TIMESTAMP")
    long countActiveTickets();

    // 3. vé đã hết hạn (status = INACTIVE hoặc đã qua ngày hết hạn)
    @Query("SELECT COUNT(t) FROM TicketEntity t WHERE t.status = 'INACTIVE' OR (t.status = 'ACTIVE' AND t.expireAt < CURRENT_TIMESTAMP)")
    long countExpiredTickets();

    // 4. vé sắp hết hạn (ACTIVE, còn hạn, nhưng ngày hết hạn <= 5 ngày tới)
    @Query("SELECT COUNT(t) FROM TicketEntity t WHERE t.status = 'ACTIVE' AND t.expireAt >= CURRENT_TIMESTAMP AND t.expireAt <= :in5Days")
    long countExpiringSoonTickets(@Param("in5Days") java.time.LocalDateTime in5Days);

    // 5. vé chưa thanh toán
    @Query("SELECT COUNT(t) FROM TicketEntity t WHERE t.status = 'DEAD'")
    long countDeadTickets();

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TicketEntity t SET t.status = 'INACTIVE' WHERE t.status = 'ACTIVE' AND t.expireAt < :now")
    int bulkUpdateExpiredTickets(@Param("now") LocalDateTime now);

    Optional<TicketEntity> findFirstByVehicleIdOrderByExpireAtDesc(Long vehicleId);

}