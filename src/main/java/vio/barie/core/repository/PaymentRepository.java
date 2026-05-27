package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.PaymentEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findById(Long id);
    Optional<PaymentEntity> findByInvoiceNo(String invoiceNo);
    boolean existsById(Long id);
    boolean existsBySepayTxId(Long sepayTxId);

    // Stats / Reconciliation

    @Query("""
            SELECT COUNT(p) FROM PaymentEntity p
            WHERE p.status = :status
              AND p.paidAt >= :from AND p.paidAt < :to
            """)
    long countByStatusAndPaidAtBetween(@Param("status") String status,
                                       @Param("from") LocalDateTime from,
                                       @Param("to")   LocalDateTime to);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM PaymentEntity p
            WHERE p.status = :status
              AND p.paidAt >= :from AND p.paidAt < :to
            """)
    BigDecimal sumAmountByStatusAndPaidAtBetween(@Param("status") String status,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to")   LocalDateTime to);

    @Query("""
            SELECT p.callbackType,
                   COUNT(p),
                   COALESCE(SUM(p.amount), 0)
            FROM PaymentEntity p
            WHERE p.status = :status
              AND p.paidAt >= :from AND p.paidAt < :to
            GROUP BY p.callbackType
            """)
    java.util.List<Object[]> revenueGroupByCallbackType(@Param("status") String status,
                                                        @Param("from") LocalDateTime from,
                                                        @Param("to")   LocalDateTime to);

    @Query(value = "SELECT * FROM payment " +
            "WHERE callback_type = 'SESSION' " +
            "AND status = 'PENDING' " +
            "AND callback_context->>'sessionId' = :sessionId",
            nativeQuery = true)
    List<PaymentEntity> findPendingBySessionId(@Param("sessionId") String sessionId);

    @Query(value = "SELECT * FROM payment " +
            "WHERE callback_type = 'TICKET' " +
            "AND status = 'PENDING' " +
            "AND callback_context->>'ticketId' = :ticketId",
            nativeQuery = true)
    List<PaymentEntity> findPendingByTicketId(@Param("ticketId") String ticketId);

    @Query(value = "SELECT * FROM payment " +
            "WHERE callback_type = 'SESSION' " +
            "AND callback_context->>'sessionId' = :sessionId " +
            "ORDER BY created_at DESC LIMIT 1",
            nativeQuery = true)
    Optional<PaymentEntity> findLatestBySessionId(@Param("sessionId") String sessionId);
}
