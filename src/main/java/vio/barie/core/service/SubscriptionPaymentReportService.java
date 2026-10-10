package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.entity.VehicleSubscriptionReceiptEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Subscription cash comes from verified receipts, never from the quoted payment amount.
 * Reports are VND only. Other currencies remain available in the receipt audit API.
 * Subscription receipt date filters/buckets use UTC, matching subscription payment timestamps.
 * Legacy timestamp reporting remains unchanged by this module.
 * Unknown paidAt uses receivedAt for reporting only, without changing financial evidence.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionPaymentReportService {
    private final EntityManager entityManager;

    public CashTotals summarize(LocalDateTime from, LocalDateTime to, Long parkingLotId, Long buildingId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<VehicleSubscriptionReceiptEntity> receipt = query.from(VehicleSubscriptionReceiptEntity.class);
        Root<VehicleSubscriptionEntity> subscription = query.from(VehicleSubscriptionEntity.class);
        query.select(cb.tuple(selections(cb, receipt).toArray(Selection<?>[]::new)));
        query.where(filters(cb, query, receipt, subscription, from, to, parkingLotId, buildingId));
        return totals(entityManager.createQuery(query).getSingleResult());
    }

    public List<DailyCash> daily(LocalDateTime from, LocalDateTime to, Long parkingLotId, Long buildingId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<VehicleSubscriptionReceiptEntity> receipt = query.from(VehicleSubscriptionReceiptEntity.class);
        Root<VehicleSubscriptionEntity> subscription = query.from(VehicleSubscriptionEntity.class);
        Expression<LocalDate> day = cb.function("date", LocalDate.class,
                cb.function("timezone", LocalDateTime.class, cb.literal("UTC"), reportTime(cb, receipt)));
        var selections = new ArrayList<>(selections(cb, receipt));
        selections.add(day.alias("day"));
        query.select(cb.tuple(selections.toArray(Selection<?>[]::new)));
        query.where(filters(cb, query, receipt, subscription, from, to, parkingLotId, buildingId));
        query.groupBy(day);
        query.orderBy(cb.desc(day));
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new DailyCash(row.get("day", LocalDate.class), totals(row))).toList();
    }

    private List<Selection<?>> selections(
            CriteriaBuilder cb, Root<VehicleSubscriptionReceiptEntity> receipt) {
        Expression<BigDecimal> amount = receipt.get("amountVnd").as(BigDecimal.class);
        Expression<BigDecimal> applied = cb.<BigDecimal>selectCase()
                .when(cb.equal(receipt.get("outcome"), SubscriptionReceiptOutcome.APPLIED), amount)
                .otherwise(BigDecimal.ZERO);
        Expression<BigDecimal> review = cb.<BigDecimal>selectCase()
                .when(cb.equal(receipt.get("outcome"), SubscriptionReceiptOutcome.REVIEW_REQUIRED), amount)
                .otherwise(BigDecimal.ZERO);
        return List.of(cb.coalesce(cb.sum(amount), BigDecimal.ZERO).alias("receivedAmount"),
                cb.coalesce(cb.sum(applied), BigDecimal.ZERO).alias("appliedAmount"),
                cb.coalesce(cb.sum(review), BigDecimal.ZERO).alias("reviewAmount"),
                cb.count(receipt).alias("receivedCount"),
                cb.coalesce(cb.sum(cb.<Long>selectCase()
                        .when(cb.equal(receipt.get("outcome"), SubscriptionReceiptOutcome.APPLIED), 1L)
                        .otherwise(0L)), 0L).alias("appliedCount"));
    }

    private Predicate[] filters(CriteriaBuilder cb, CriteriaQuery<?> query,
                                Root<VehicleSubscriptionReceiptEntity> receipt,
                                Root<VehicleSubscriptionEntity> subscription,
                                LocalDateTime from, LocalDateTime to, Long parkingLotId, Long buildingId) {
        List<Predicate> filters = new ArrayList<>();
        filters.add(cb.equal(receipt.get("subscriptionId"), subscription.get("id")));
        filters.add(cb.equal(receipt.get("currency"), "VND"));
        Expression<Instant> time = reportTime(cb, receipt);
        if (from != null) filters.add(cb.greaterThanOrEqualTo(time, from.toInstant(ZoneOffset.UTC)));
        if (to != null) filters.add(cb.lessThan(time, to.toInstant(ZoneOffset.UTC)));
        if (parkingLotId != null) filters.add(cb.equal(subscription.get("parkingLotId"), parkingLotId));
        if (buildingId != null) {
            Root<ParkingLotEntity> lot = query.from(ParkingLotEntity.class);
            filters.add(cb.equal(subscription.get("parkingLotId"), lot.get("id")));
            filters.add(cb.equal(lot.get("building").get("id"), buildingId));
        }
        return filters.toArray(Predicate[]::new);
    }

    private Expression<Instant> reportTime(CriteriaBuilder cb, Root<VehicleSubscriptionReceiptEntity> receipt) {
        return cb.coalesce(receipt.<Instant>get("paidAt"), receipt.<Instant>get("receivedAt"));
    }

    private CashTotals totals(Tuple row) {
        return new CashTotals(row.get("receivedAmount", BigDecimal.class),
                row.get("appliedAmount", BigDecimal.class), row.get("reviewAmount", BigDecimal.class),
                ((Number) row.get("receivedCount")).longValue(), ((Number) row.get("appliedCount")).longValue());
    }

    public record CashTotals(BigDecimal receivedAmount, BigDecimal appliedAmount,
                             BigDecimal reviewAmount, long receivedCount, long appliedCount) { }
    public record DailyCash(LocalDate day, CashTotals totals) { }
}
