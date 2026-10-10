package vio.barie.core.repository;

import jakarta.persistence.Tuple;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.PaymentEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {
    Optional<PaymentEntity> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> lockById(@Param("id") Long id);

    @Query(value = """
            SELECT * FROM payment
            WHERE callback_type = 'SUBSCRIPTION' AND status = 'PENDING'
              AND callback_context->>'subscriptionId' = :subscriptionId
            ORDER BY created_at DESC, id DESC LIMIT 1
            """, nativeQuery = true)
    Optional<PaymentEntity> findLatestPendingBySubscriptionId(@Param("subscriptionId") String subscriptionId);

    Optional<PaymentEntity> findByInvoiceNo(String invoiceNo);

    /** JSONB routing data is inspected through JPA without loading payment history or joining entities. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM payment
                WHERE callback_type = 'SESSION'
                  AND callback_context->>'sessionId' = :sessionId
                  AND NOT jsonb_exists(callback_context, 'calculationId')
                  AND NOT jsonb_exists(callback_context, 'parkingFeePaymentId')
            )
            """, nativeQuery = true)
    boolean existsLegacySessionPaymentHistory(@Param("sessionId") String sessionId);

    boolean existsById(Long id);

    boolean existsBySepayTxId(String sepayTxId);

    @Query(value = """
            SELECT
                COALESCE(SUM(CASE WHEN p.status = 'PAID' AND p.callback_type IS DISTINCT FROM 'SUBSCRIPTION'
                                  THEN p.amount ELSE 0 END), 0) AS revenue,
                COUNT(CASE WHEN p.status = 'PAID' THEN 1 END)                          AS successful_count,
                COUNT(CASE WHEN p.status = 'PENDING' THEN 1 END)                       AS pending_count,
                COUNT(CASE WHEN p.status NOT IN ('PAID', 'PENDING') THEN 1 END)         AS failed_count
            FROM payment p
            LEFT JOIN parking_session ps
                ON p.callback_type = 'SESSION'
               AND (p.callback_context->>'sessionId')::bigint = ps.id
            LEFT JOIN vehicle_subscription vs
                ON p.callback_type = 'SUBSCRIPTION'
               AND (p.callback_context->>'subscriptionId')::bigint = vs.id
            LEFT JOIN parking_lot pl ON COALESCE(ps.parking_lot_id, vs.parking_lot_id) = pl.id
            WHERE (
                    CAST(:from AS timestamp) IS NULL
                    OR COALESCE(p.paid_at, p.created_at) >= CAST(:from AS timestamp)
                  )
              AND (
                    CAST(:to AS timestamp) IS NULL
                    OR COALESCE(p.paid_at, p.created_at) < CAST(:to AS timestamp)
                  )
              AND (
                    (:parkingLotId IS NULL AND :buildingId IS NULL)
                    OR (
                        pl.id IS NOT NULL
                        AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                        AND (:buildingId   IS NULL OR pl.building_id = :buildingId)
                    )
                  )
            """, nativeQuery = true)
    Tuple getRevenueSummary(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("parkingLotId") Long parkingLotId,
            @Param("buildingId") Long buildingId);


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

    @Query(value = """
            SELECT DISTINCT ON ((callback_context->>'sessionId')::bigint) *
            FROM payment
            WHERE callback_type = 'SESSION'
            AND (callback_context->>'sessionId')::bigint IN (:sessionIds)
            ORDER BY (callback_context->>'sessionId')::bigint, created_at DESC
            """, nativeQuery = true)
    List<PaymentEntity> findLatestAnyStatusBySessionIds(@Param("sessionIds") List<Long> sessionIds);

    @Query(value = """
            SELECT
                COALESCE(v_s.plate_number, v_t.plate_number, v_sub.plate_number) AS plate_number,
                CASE p.callback_type
                    WHEN 'SESSION' THEN 'Trả vé ngày'
                    WHEN 'TICKET'  THEN 'Đóng vé tháng'
                    WHEN 'SUBSCRIPTION' THEN 'Đóng thuê bao'
                    ELSE p.callback_type
                END AS type,
                p.amount,
                p.payment_method,
                p.status,
                p.invoice_no,
                p.description,
                COALESCE(p.paid_at, p.created_at) AS time
            FROM payment p
            LEFT JOIN parking_session ps
                ON p.callback_type = 'SESSION'
               AND (p.callback_context->>'sessionId')::bigint = ps.id
            LEFT JOIN vehicle_subscription vs
                ON p.callback_type = 'SUBSCRIPTION'
               AND (p.callback_context->>'subscriptionId')::bigint = vs.id
            LEFT JOIN parking_lot pl ON COALESCE(ps.parking_lot_id, vs.parking_lot_id) = pl.id
            LEFT JOIN vehicle v_s ON ps.vehicle_id = v_s.id
            LEFT JOIN vehicle v_sub ON vs.vehicle_id = v_sub.id
            LEFT JOIN ticket t
                ON p.callback_type = 'TICKET'
               AND (p.callback_context->>'ticketId')::bigint = t.id
            LEFT JOIN vehicle v_t ON t.vehicle_id = v_t.id
            WHERE (
                :statusFilter = 'ALL'
                OR (:statusFilter = 'OTHER' AND p.status NOT IN ('PAID', 'PENDING'))
                OR p.status = :statusFilter
            )
              AND (
                    (:parkingLotId IS NULL AND :buildingId IS NULL)
                    OR (
                        pl.id IS NOT NULL
                        AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                        AND (:buildingId IS NULL OR pl.building_id = :buildingId)
                    )
                  )
              AND (
                    CAST(:from AS timestamp) IS NULL
                    OR COALESCE(p.paid_at, p.created_at) >= CAST(:from AS timestamp)
                  )
              AND (
                    CAST(:to AS timestamp) IS NULL
                    OR COALESCE(p.paid_at, p.created_at) < CAST(:to AS timestamp)
            )
            ORDER BY COALESCE(p.paid_at, p.created_at) DESC NULLS LAST
            """,
            countQuery = """
                    SELECT COUNT(p.id)
                    FROM payment p
                    LEFT JOIN parking_session ps
                        ON p.callback_type = 'SESSION'
                       AND (p.callback_context->>'sessionId')::bigint = ps.id
                    LEFT JOIN vehicle_subscription vs
                        ON p.callback_type = 'SUBSCRIPTION'
                       AND (p.callback_context->>'subscriptionId')::bigint = vs.id
                    LEFT JOIN parking_lot pl ON COALESCE(ps.parking_lot_id, vs.parking_lot_id) = pl.id
                    WHERE (
                        :statusFilter = 'ALL'
                        OR (:statusFilter = 'OTHER' AND p.status NOT IN ('PAID', 'PENDING'))
                        OR p.status = :statusFilter
                    )
                      AND (
                            (:parkingLotId IS NULL AND :buildingId IS NULL)
                            OR (
                                pl.id IS NOT NULL
                                AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                                AND (:buildingId IS NULL OR pl.building_id = :buildingId)
                            )
                          )
                      AND (
                            CAST(:from AS timestamp) IS NULL
                            OR COALESCE(p.paid_at, p.created_at) >= CAST(:from AS timestamp)
                          )
                      AND (
                            CAST(:to AS timestamp) IS NULL
                            OR COALESCE(p.paid_at, p.created_at) < CAST(:to AS timestamp)
                    )
                    """,
            nativeQuery = true)
    Page<Object[]> findPaymentListRaw(
            @Param("statusFilter") String statusFilter,
            @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query(value = """
            SELECT
                TO_CHAR(DATE(p.paid_at), 'DD/MM/YYYY') AS day,
                COUNT(p.id)                             AS transaction_count,
                COALESCE(SUM(p.amount), 0)              AS revenue
            FROM payment p
            LEFT JOIN parking_session ps
                ON p.callback_type = 'SESSION'
               AND (p.callback_context->>'sessionId')::bigint = ps.id
            LEFT JOIN parking_lot pl ON ps.parking_lot_id = pl.id
            WHERE DATE(p.paid_at) NOT IN (:excludeDates)
              AND p.status = 'PAID'
              AND p.callback_type IS DISTINCT FROM 'SUBSCRIPTION'
              AND (
                    CAST(:startDate AS timestamp) IS NULL
                    OR p.paid_at >= CAST(:startDate AS timestamp)
                  )
              AND (
                    CAST(:endDate AS timestamp) IS NULL
                    OR p.paid_at < CAST(:endDate AS timestamp)
                  )
              AND (
                    (:parkingLotId IS NULL AND :buildingId IS NULL)
                    OR (
                        pl.id IS NOT NULL
                        AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                        AND (:buildingId IS NULL OR pl.building_id = :buildingId)
                    )
                  )
            GROUP BY DATE(p.paid_at)
            ORDER BY DATE(p.paid_at) DESC
            """, nativeQuery = true)
    List<Object[]> findDailyRevenue(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("excludeDates") List<LocalDate> excludeDates,
            @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId);
    @Query(value = """
           SELECT p.callback_type,
                count(p.id),
                COALESCE(SUM(p.amount), 0)
                FROM payment p
                WHERE p.status = 'PAID'
                    AND p.callback_type IN ('SESSION', 'TICKET')
                    AND p.paid_at >= :startDate
                    AND p.paid_at < :endDate 
                    GROUP BY p.callback_type """, nativeQuery = true)
    List<Object[]> countAndSumRevenueByTicketType(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}
