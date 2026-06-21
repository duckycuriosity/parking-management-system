package vio.barie.core.repository;

import jakarta.persistence.Tuple;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.dto.payment.RevenueSummaryResponse;
import vio.barie.core.entity.PaymentEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findById(Long id);
    Optional<PaymentEntity> findByInvoiceNo(String invoiceNo);
    boolean existsById(Long id);
    boolean existsBySepayTxId(String sepayTxId);

    @Query(value = """
            SELECT
                COALESCE(SUM(p.amount), 0) AS revenue,
                COUNT(p.id)                AS count
            FROM payment p
            LEFT JOIN parking_session ps
                ON p.callback_type = 'SESSION'
               AND (p.callback_context->>'sessionId')::bigint = ps.id
            LEFT JOIN parking_lot pl ON ps.parking_lot_id = pl.id
            WHERE p.status = :status
              AND p.paid_at >= :from
              AND p.paid_at < :to
              AND (
                    (:parkingLotId IS NULL AND :buildingId IS NULL)
                    OR (
                        pl.id IS NOT NULL
                        AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                        AND (:buildingId   IS NULL OR pl.building_id = :buildingId)
                    )
                  )
            """, nativeQuery = true)
    Tuple sumAndCountByStatus(
            @Param("status")       String        status,
            @Param("from")         LocalDateTime from,
            @Param("to")           LocalDateTime to,
            @Param("parkingLotId") Long          parkingLotId,
            @Param("buildingId")   Long          buildingId);


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

    /**
     * Tìm payment mới nhất gắn với 1 ticket (callback_type=TICKET, ticketId trong jsonb).
     * Native query vì callback_context là JSONB của Postgres.
     */
    @Query(value = """
            SELECT * FROM payment
            WHERE callback_type = 'TICKET'
              AND callback_context->>'ticketId' = :ticketId
            ORDER BY created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<PaymentEntity> findLatestByTikcetId(@Param("ticketId") String ticketId);

    @Query(value = """
            SELECT DISTINCT ON ((callback_context->>'sessionId')::bigint) *
            FROM payment
            WHERE callback_type = 'SESSION'
            AND status = 'PAID'
            AND (callback_context->>'sessionId')::bigint IN (:sessionIds)
            ORDER BY (callback_context->>'sessionId')::bigint, created_at DESC
            """, nativeQuery = true)
    List<PaymentEntity> findLatestBySessionIds(@Param("sessionIds") List<Long> sessionIds);
}
