package vio.barie.core.persistence;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import vio.barie.core.common.SubscriptionPeriodStatus;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.subscription.RegisterSubscriptionRequest;
import vio.barie.core.dto.subscription.RenewSubscriptionRequest;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.dto.subscription.VerifiedSubscriptionPayment;
import vio.barie.core.entity.*;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.repository.VehicleSubscriptionReceiptRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.service.VehicleSubscriptionService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calls the real service through Spring transaction advice with real repositories and PaymentEntity.
 * Only this disposable schema is created: Hibernate generates the legacy payment fixture first,
 * then Flyway applies V54 and V55 and Hibernate validates the combined mappings. No application profile,
 * gateway, IoT bean or production connection is loaded.
 */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class VehicleSubscriptionServicePostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");
    private static final Instant START = Instant.parse("2026-10-03T07:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(3600);
    private String schema;
    private Connection database;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transaction;
    private PaymentRepository payments;
    private VehicleSubscriptionRepository subscriptions;
    private VehicleSubscriptionReceiptRepository receipts;
    private EntityManager entityManager;
    private VehicleSubscriptionService service;
    private AdjustableClock clock;
    private long packageId;
    private volatile Consumer<Object> eventHandler = event -> { };

    @BeforeEach
    void createIsolatedServiceAndDatabase() throws SQLException {
        schema = "subscription_service_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = openRaw()) {
            execute(administration, "CREATE SCHEMA " + schema);
        }
        database = open();
        // Generate real legacy mappings, including the JPQL Vehicle/ParkingLot targets.
        // Subscription tables originate from V54; V55 moves their business rules to Java.
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "55");
        packageId = number("""
                INSERT INTO subscription_package
                    (package_code, name, version_no, parking_lot_id, vehicle_type, price_vnd,
                     duration_days, coverage_mode, effective_from, status)
                VALUES ('MONTHLY', 'Test monthly package', 1, 1, 'CAR', 500000, 30, 'ALL_DAY', ?, 'PUBLISHED')
                RETURNING id
                """, NOW.minusSeconds(60));
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema,
                SubscriptionJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        assertNotNull(emf);
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        var repositories = new JpaRepositoryFactory(entityManager);
        // Spring's production JpaRepositoryFactoryBean installs this translation advice.
        // A standalone factory otherwise exposes Hibernate exceptions directly, bypassing
        // the service's named-constraint handling tested here.
        repositories.addRepositoryProxyPostProcessor((proxy, metadata) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        payments = repositories.getRepository(PaymentRepository.class);
        subscriptions = repositories.getRepository(VehicleSubscriptionRepository.class);
        receipts = repositories.getRepository(VehicleSubscriptionReceiptRepository.class);
        JpaTransactionManager manager = new JpaTransactionManager(emf);
        transaction = new TransactionTemplate(manager);
        clock = new AdjustableClock(NOW);
        VehicleSubscriptionService target = new VehicleSubscriptionService(
                subscriptions,
                repositories.getRepository(SubscriptionPackageRepository.class),
                receipts, payments,
                clock, entityManager, event -> eventHandler.accept(event));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (VehicleSubscriptionService) proxy.getProxy();
    }

    @AfterEach
    void discardOnlyThisSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (database != null) database.close();
        if (schema != null) {
            try (Connection administration = openRaw()) {
                execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void actualRegistrationPaymentReplaySecondTransferAndEarlyRenewalPersistCorrectly() throws SQLException {
        SubscriptionResponse original = register();
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, original.getStatus());
        assertTrue(service.current(1L, 1L).isEmpty());
        assertTrue(service.paymentReceipts(original.getId()).isEmpty());
        PaymentEntity intent = payment(original.getId());
        VerifiedSubscriptionPayment first = evidence(original.getId(), intent, "first", 500000);
        SubscriptionResponse granted = service.confirmVerifiedPayment(first);
        assertEquals(VehicleSubscriptionStatus.PAID, granted.getStatus());
        assertEquals(SubscriptionPeriodStatus.IN_TERM, granted.getPeriodStatus());
        assertNotNull(granted.getPaidReceiptId());
        assertEquals(granted.getPaidReceiptId(), service.confirmVerifiedPayment(first).getPaidReceiptId());
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        service.confirmVerifiedPayment(evidence(original.getId(), intent, "second", 400000));
        var receipts = service.paymentReceipts(original.getId());
        assertEquals(2, receipts.size());
        assertEquals(SubscriptionReceiptOutcome.APPLIED, receipts.get(0).outcome());
        assertEquals(granted.getPaidReceiptId(), receipts.get(0).id());
        assertEquals(400000L, receipts.get(1).amountVnd());
        assertEquals("SECOND_SETTLEMENT", receipts.get(1).reasonCode());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, receipts.get(1).outcome());
        assertEquals(1, number("SELECT count(*) FROM payment WHERE status = 'PAID' AND reference_code = 'first'"));
        RenewSubscriptionRequest renewalRequest = new RenewSubscriptionRequest();
        renewalRequest.setPreviousSubscriptionId(original.getId());
        renewalRequest.setPackageId(packageId);
        renewalRequest.setRequestKey(UUID.randomUUID().toString());
        renewalRequest.setPaymentDeadlineAt(DEADLINE);
        SubscriptionResponse renewal = service.renew(renewalRequest);
        assertEquals(original.getEndsAt(), renewal.getStartsAt());
        SubscriptionResponse future = service.confirmVerifiedPayment(
                evidence(renewal.getId(), payment(renewal.getId()), "renewal", 500000));
        assertEquals(SubscriptionPeriodStatus.UPCOMING, future.getPeriodStatus());
        assertEquals(2, service.history(1L, 1L).size());
        assertEquals(1, service.current(1L, 1L).size());
        assertEquals(original.getEndsAt(), service.getById(original.getId()).getEndsAt());
    }

    @Test
    void failureAfterAllWritesRollsBackReceiptIntentAndPeriodThenAllowsRetry() throws SQLException {
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());
        VerifiedSubscriptionPayment evidence = evidence(order.getId(), intent, "rollback", 500000);
        execute(database, "ALTER TABLE payment ADD CONSTRAINT test_injected_flush_failure CHECK (status <> 'PAID')");

        assertThrows(DataIntegrityViolationException.class, () -> service.confirmVerifiedPayment(evidence));

        assertEquals(0, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number("SELECT count(*) FROM payment WHERE id = ? AND status = 'PENDING'", intent.getId()));
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, service.getById(order.getId()).getStatus());
        assertTrue(service.current(1L, 1L).isEmpty());
        execute(database, "ALTER TABLE payment DROP CONSTRAINT test_injected_flush_failure");
        assertEquals(VehicleSubscriptionStatus.PAID, service.confirmVerifiedPayment(evidence).getStatus());
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt"));
    }

    @Test
    void amountMismatchCommitsActualMoneyForReviewWithoutGrantingRights() throws SQLException {
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());

        SubscriptionResponse result = service.confirmVerifiedPayment(evidence(order.getId(), intent, "mismatch", 490000));

        assertEquals(VehicleSubscriptionStatus.PAYMENT_EXCEPTION, result.getStatus());
        assertNull(result.getPaidReceiptId());
        assertTrue(service.current(1L, 1L).isEmpty());
        assertEquals(1, number("SELECT count(*) FROM payment WHERE status = 'REVIEW_REQUIRED' AND amount = 500000"));
        var receipt = service.paymentReceipts(order.getId()).getFirst();
        assertEquals(490000L, receipt.amountVnd());
        assertEquals("AMOUNT_MISMATCH", receipt.reasonCode());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, receipt.outcome());
    }

    @Test
    void competingServiceCallbacksSerializeAndRetainLosersMoneyForReview() throws Exception {
        SubscriptionResponse first = register();
        SubscriptionResponse second = register();
        PaymentEntity firstIntent = payment(first.getId());
        PaymentEntity secondIntent = payment(second.getId());
        CountDownLatch writesFinished = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var winner = workers.submit(() -> holdBeforeCommit(
                    () -> service.confirmVerifiedPayment(evidence(first.getId(), firstIntent, "winner", 500000)),
                    writesFinished, allowCommit));
            assertTrue(writesFinished.await(5, TimeUnit.SECONDS));
            var loser = workers.submit(() -> service.confirmVerifiedPayment(evidence(second.getId(), secondIntent, "loser", 500000)));
            try {
                awaitDatabaseLock();
            } finally {
                allowCommit.countDown();
            }
            assertEquals(VehicleSubscriptionStatus.PAID, winner.get(10, TimeUnit.SECONDS).getStatus());
            assertEquals(VehicleSubscriptionStatus.PAYMENT_EXCEPTION, loser.get(10, TimeUnit.SECONDS).getStatus());
            assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
            assertEquals(2, number("SELECT count(*) FROM vehicle_subscription_receipt"));
            assertEquals(1, number("SELECT count(*) FROM payment WHERE status = 'PAID'"));
            assertEquals(1, number("SELECT count(*) FROM payment WHERE status = 'REVIEW_REQUIRED'"));
            assertEquals("PAID_PERIOD_OVERLAP", service.paymentReceipts(second.getId()).getFirst().reasonCode());
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void settlementHookRequiresCallersWriteTransactionUsingRealSpringAdvice() {
        SubscriptionResponse order = register();
        service.confirmVerifiedPayment(evidence(order.getId(), payment(order.getId()), "settlement", 500000));
        assertThrows(IllegalTransactionStateException.class,
                () -> service.lockPaidPeriodsForSettlement(1L, 1L, START, order.getEndsAt()));
        transaction.setReadOnly(true);
        assertThrows(BusinessException.class, () -> transaction.execute(status ->
                service.lockPaidPeriodsForSettlement(1L, 1L, START, order.getEndsAt())));
        transaction.setReadOnly(false);
        var periods = transaction.execute(status -> service.lockPaidPeriodsForSettlement(1L, 1L, START, order.getEndsAt()));
        assertNotNull(periods);
        assertEquals(order.getId(), periods.getFirst().getId());
    }

    @Test
    void threeHundredThousandInvoiceRetainsSixHundredThousandActualMoneyButGrantsOnePeriod() throws SQLException {
        packageId = number("""
                INSERT INTO subscription_package
                    (package_code, name, version_no, parking_lot_id, vehicle_type, price_vnd,
                     duration_days, coverage_mode, effective_from, status)
                VALUES ('THREE_HUNDRED', '300000 package', 1, 1, 'CAR', 300000, 30, 'ALL_DAY', ?, 'PUBLISHED')
                RETURNING id
                """, NOW.minusSeconds(60));
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());
        VerifiedSubscriptionPayment first = evidence(order.getId(), intent, "TX001", 300000);
        long firstReceipt = service.confirmVerifiedPayment(first).getPaidReceiptId();
        assertEquals(firstReceipt, service.confirmVerifiedPayment(first).getPaidReceiptId());
        assertEquals(firstReceipt, service.confirmVerifiedPayment(
                evidence(order.getId(), intent, "TX002", 300000)).getPaidReceiptId());

        assertEquals(1, number("SELECT count(*) FROM payment"));
        assertEquals(2, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(600000, number("SELECT sum(amount_vnd) FROM vehicle_subscription_receipt"));
        assertEquals(300000, number("SELECT amount FROM payment WHERE id = ?", intent.getId()));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt WHERE outcome = 'APPLIED'"));
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt WHERE reason_code = 'SECOND_SETTLEMENT'"));
        assertEquals(firstReceipt, service.getById(order.getId()).getPaidReceiptId());
    }

    @Test
    void jpaLifecycleGuardsFreezeQuotedOrdersPaidPeriodsAndReceiptsWithoutBusinessTriggers() throws SQLException {
        SubscriptionResponse order = register();
        assertImmutable(() -> transaction.executeWithoutResult(status -> {
            VehicleSubscriptionEntity managed = subscriptions.findById(order.getId()).orElseThrow();
            managed.setPriceVnd(700000L);
            entityManager.flush();
        }));
        assertEquals(500000, number("SELECT price_vnd FROM vehicle_subscription WHERE id = ?", order.getId()));

        long receiptId = service.confirmVerifiedPayment(
                evidence(order.getId(), payment(order.getId()), "frozen", 500000)).getPaidReceiptId();
        assertImmutable(() -> transaction.executeWithoutResult(status -> {
            VehicleSubscriptionEntity managed = subscriptions.findById(order.getId()).orElseThrow();
            managed.setStatus(VehicleSubscriptionStatus.CANCELLED);
            managed.setPaidAt(null);
            managed.setPaidReceiptId(null);
            entityManager.flush();
        }));
        assertImmutable(() -> transaction.executeWithoutResult(status ->
                entityManager.remove(subscriptions.findById(order.getId()).orElseThrow())));
        assertImmutable(() -> transaction.executeWithoutResult(status -> {
            VehicleSubscriptionReceiptEntity managed = receipts.findById(receiptId).orElseThrow();
            managed.setAmountVnd(400000L);
            entityManager.flush();
        }));
        assertImmutable(() -> transaction.executeWithoutResult(status ->
                entityManager.remove(receipts.findById(receiptId).orElseThrow())));
        assertEquals(500000, number("SELECT amount_vnd FROM vehicle_subscription_receipt WHERE id = ?", receiptId));
        assertEquals(receiptId, service.getById(order.getId()).getPaidReceiptId());
    }

    @Test
    void v55KeepsStructuralConstraintsAndRemovesSubscriptionSqlBusinessDependencies() throws SQLException {
        assertEquals(0, number("""
                SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = ? AND c.contype IN ('c', 'x')
                  AND c.conrelid IN ('subscription_package'::regclass, 'subscription_package_schedule'::regclass,
                                    'vehicle_subscription'::regclass, 'vehicle_subscription_receipt'::regclass)
                """, schema));
        assertEquals(0, number("""
                SELECT count(*) FROM pg_trigger t
                WHERE NOT t.tgisinternal AND t.tgrelid IN
                    ('subscription_package'::regclass, 'subscription_package_schedule'::regclass,
                     'vehicle_subscription'::regclass, 'vehicle_subscription_receipt'::regclass)
                """));
        assertEquals(0, number("""
                SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ? AND p.proname IN
                    ('enforce_subscription_package_contract', 'enforce_subscription_schedule_immutability',
                     'enforce_vehicle_subscription_contract', 'enforce_subscription_receipt_immutability',
                     'ensure_applied_subscription_receipt_grants_period')
                """, schema));
        assertTrue(number("""
                SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = ? AND c.contype = 'f'
                  AND c.conrelid IN ('subscription_package'::regclass, 'subscription_package_schedule'::regclass,
                                    'vehicle_subscription'::regclass, 'vehicle_subscription_receipt'::regclass)
                """, schema) > 0);
        SubscriptionResponse order = register();
        SubscriptionResponse paid = service.confirmVerifiedPayment(
                evidence(order.getId(), payment(order.getId()), "java-contract", 500000));
        assertEquals(1, number("""
                SELECT count(*) FROM vehicle_subscription s JOIN vehicle_subscription_receipt r
                  ON r.id = s.paid_receipt_id AND r.subscription_id = s.id
                WHERE s.id = ? AND s.status = 'PAID' AND r.outcome = 'APPLIED'
                  AND r.amount_vnd = s.price_vnd AND r.currency = 'VND' AND r.paid_at = s.paid_at
                """, paid.getId()));
    }

    @Test
    void identicalConcurrentCallbacksReturnSameReceiptAndPublishOnceAfterCommittedDataIsVisible() throws Exception {
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());
        VerifiedSubscriptionPayment evidence = evidence(order.getId(), intent, "concurrent-replay", 500000);
        java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
        eventHandler = event -> {
            try (Connection observer = open()) {
                assertEquals(1, number(observer, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
                assertEquals(1, number(observer, "SELECT count(*) FROM vehicle_subscription_receipt WHERE outcome = 'APPLIED'"));
                assertEquals(1, number(observer, "SELECT count(*) FROM payment WHERE status = 'PAID'"));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            delivered.incrementAndGet();
        };
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SubscriptionResponse> first = workers.submit(() -> holdBeforeCommit(
                    () -> service.confirmVerifiedPayment(evidence), written, commit));
            assertTrue(written.await(5, TimeUnit.SECONDS));
            assertEquals(0, delivered.get());
            Future<SubscriptionResponse> replay = workers.submit(() -> service.confirmVerifiedPayment(evidence));
            try { awaitDatabaseLock(); } finally { commit.countDown(); }
            assertEquals(first.get(10, TimeUnit.SECONDS).getPaidReceiptId(),
                    replay.get(10, TimeUnit.SECONDS).getPaidReceiptId());
            assertEquals(1, delivered.get());
            assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        } finally {
            commit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSameRequestKeyReturnsOneOrderAndConflictingCrossVehicleKeyReturns409() throws Exception {
        String key = UUID.randomUUID().toString();
        RegisterSubscriptionRequest original = request(1L, key);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SubscriptionResponse> first = workers.submit(() -> holdBeforeCommit(
                    () -> service.register(original), written, commit));
            assertTrue(written.await(5, TimeUnit.SECONDS));
            Future<SubscriptionResponse> replay = workers.submit(() -> service.register(original));
            try { awaitDatabaseLock(); } finally { commit.countDown(); }
            assertEquals(first.get(10, TimeUnit.SECONDS).getId(), replay.get(10, TimeUnit.SECONDS).getId());
            assertEquals(1, number("SELECT count(*) FROM vehicle_subscription"));
        } finally {
            commit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }

        String globalKey = UUID.randomUUID().toString();
        CountDownLatch globalWritten = new CountDownLatch(1);
        CountDownLatch globalCommit = new CountDownLatch(1);
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        try {
            Future<SubscriptionResponse> winner = contenders.submit(() -> holdBeforeCommit(
                    () -> service.register(request(1L, globalKey)), globalWritten, globalCommit));
            assertTrue(globalWritten.await(5, TimeUnit.SECONDS));
            Future<SubscriptionResponse> conflict = contenders.submit(() -> service.register(request(2L, globalKey)));
            try { awaitDatabaseLock(); } finally { globalCommit.countDown(); }
            assertNotNull(winner.get(10, TimeUnit.SECONDS).getId());
            assertConcurrentConflict(conflict);
            assertEquals(1, number("SELECT count(*) FROM vehicle_subscription WHERE request_key = ?", globalKey));
            assertEquals(0, number("SELECT count(*) FROM vehicle_subscription WHERE request_key = ? AND vehicle_id = 2", globalKey));
        } finally {
            globalCommit.countDown();
            contenders.shutdownNow();
            assertTrue(contenders.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentPhysicalReferenceOnDifferentOrdersRetainsOneReceiptAndReports409() throws Exception {
        SubscriptionResponse first = service.register(request(1L, UUID.randomUUID().toString()));
        SubscriptionResponse second = service.register(request(2L, UUID.randomUUID().toString()));
        PaymentEntity firstIntent = payment(first.getId());
        PaymentEntity secondIntent = payment(second.getId());
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SubscriptionResponse> winner = workers.submit(() -> holdBeforeCommit(
                    () -> service.confirmVerifiedPayment(evidence(first.getId(), firstIntent, "global-reference", 500000)),
                    written, commit));
            assertTrue(written.await(5, TimeUnit.SECONDS));
            Future<SubscriptionResponse> conflict = workers.submit(() -> service.confirmVerifiedPayment(
                    evidence(second.getId(), secondIntent, "global-reference", 500000)));
            try { awaitDatabaseLock(); } finally { commit.countDown(); }
            assertEquals(VehicleSubscriptionStatus.PAID, winner.get(10, TimeUnit.SECONDS).getStatus());
            assertConcurrentConflict(conflict);
            assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt WHERE reference = 'global-reference'"));
            assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, service.getById(second.getId()).getStatus());
            assertEquals(1, number("SELECT count(*) FROM payment WHERE id = ? AND status = 'PENDING'", secondIntent.getId()));
        } finally {
            commit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void afterCommitDeliveryFailureDoesNotRollBackPaymentOrDuplicateRightsOnRetry() throws SQLException {
        SubscriptionResponse order = register();
        VerifiedSubscriptionPayment evidence = evidence(order.getId(), payment(order.getId()), "delivery-failure", 500000);
        java.util.concurrent.atomic.AtomicInteger deliveries = new java.util.concurrent.atomic.AtomicInteger();
        eventHandler = event -> {
            deliveries.incrementAndGet();
            throw new IllegalStateException("Simulated external delivery failure after commit");
        };
        assertEquals(VehicleSubscriptionStatus.PAID, service.confirmVerifiedPayment(evidence).getStatus());
        assertEquals(VehicleSubscriptionStatus.PAID, service.getById(order.getId()).getStatus());
        assertEquals(1, deliveries.get());
        service.confirmVerifiedPayment(evidence);
        assertEquals(1, deliveries.get());
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt"));
    }

    @Test
    void callbackAfterDeadlineStillAppliesPaymentMadeExactlyAtDeadline() {
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());
        clock.set(DEADLINE.plusSeconds(3600));
        VerifiedSubscriptionPayment delayed = new VerifiedSubscriptionPayment(order.getId(), intent.getId(),
                "SEPAY", "paid-at-deadline", intent.getInvoiceNo(), 500000L, "VND", DEADLINE,
                clock.instant(), Map.of("verified", true));
        assertEquals(VehicleSubscriptionStatus.PAID, service.confirmVerifiedPayment(delayed).getStatus());
        assertEquals(DEADLINE, service.getById(order.getId()).getPaidAt());
    }

    @Test
    void actualLatePaymentAndUnknownPaymentTimeRemainReviewEvidenceWithoutRights() throws SQLException {
        SubscriptionResponse lateOrder = register();
        PaymentEntity lateIntent = payment(lateOrder.getId());
        clock.set(DEADLINE.plusSeconds(3600));
        VerifiedSubscriptionPayment late = new VerifiedSubscriptionPayment(lateOrder.getId(), lateIntent.getId(),
                "SEPAY", "physically-late", lateIntent.getInvoiceNo(), 500000L, "VND", DEADLINE.plusSeconds(1),
                clock.instant(), Map.of("verified", true));
        assertEquals(VehicleSubscriptionStatus.PAYMENT_EXCEPTION, service.confirmVerifiedPayment(late).getStatus());
        assertEquals("PAID_AFTER_DEADLINE", service.paymentReceipts(lateOrder.getId()).getFirst().reasonCode());
        clock.set(NOW);
        SubscriptionResponse unknownOrder = register();
        PaymentEntity unknownIntent = payment(unknownOrder.getId());
        VerifiedSubscriptionPayment unknown = new VerifiedSubscriptionPayment(unknownOrder.getId(), unknownIntent.getId(),
                "SEPAY", "time-unknown", unknownIntent.getInvoiceNo(), 500000L, "VND", null, NOW,
                Map.of("verified", true));
        assertEquals(VehicleSubscriptionStatus.PAYMENT_EXCEPTION, service.confirmVerifiedPayment(unknown).getStatus());
        assertNull(service.paymentReceipts(unknownOrder.getId()).getFirst().paidAt());
        assertEquals("PAYMENT_TIME_UNKNOWN", service.paymentReceipts(unknownOrder.getId()).getFirst().reasonCode());
        assertEquals(1000000, number("SELECT sum(amount_vnd) FROM vehicle_subscription_receipt"));
        assertTrue(service.current(1L, 1L).isEmpty());
    }

    @Test
    void timelyPaymentReceivedAfterWholePeriodExpiredDoesNotShiftOrGrantPeriod() {
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());
        clock.set(order.getEndsAt());
        VerifiedSubscriptionPayment delayed = new VerifiedSubscriptionPayment(order.getId(), intent.getId(),
                "SEPAY", "expired-at-processing", intent.getInvoiceNo(), 500000L, "VND", NOW.minusSeconds(1),
                clock.instant(), Map.of("verified", true));
        SubscriptionResponse result = service.confirmVerifiedPayment(delayed);
        assertEquals(VehicleSubscriptionStatus.PAYMENT_EXCEPTION, result.getStatus());
        assertEquals(order.getStartsAt(), result.getStartsAt());
        assertEquals(order.getEndsAt(), result.getEndsAt());
        assertEquals("PERIOD_ALREADY_EXPIRED", service.paymentReceipts(order.getId()).getFirst().reasonCode());
        assertTrue(service.current(1L, 1L).isEmpty());
    }

    @Test
    void conflictingReplayDoesNotRewriteVerifiedReceiptOrPaymentEvidence() throws SQLException {
        SubscriptionResponse order = register();
        PaymentEntity intent = payment(order.getId());
        VerifiedSubscriptionPayment first = evidence(order.getId(), intent, "conflict", 500000);
        long receiptId = service.confirmVerifiedPayment(first).getPaidReceiptId();
        BusinessException conflict = assertThrows(BusinessException.class, () ->
                service.confirmVerifiedPayment(evidence(order.getId(), intent, "conflict", 400000)));
        assertEquals(409, conflict.getHttpStatus());
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(500000, number("SELECT amount_vnd FROM vehicle_subscription_receipt WHERE id = ?", receiptId));
        assertEquals(receiptId, service.getById(order.getId()).getPaidReceiptId());
    }

    private static final class AdjustableClock extends Clock {
        private volatile Instant instant;
        private AdjustableClock(Instant instant) { this.instant = instant; }
        private void set(Instant instant) { this.instant = instant; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Fixture clock is UTC");
            return this;
        }
        @Override public Instant instant() { return instant; }
    }

    private void assertConcurrentConflict(Future<?> operation) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(10, TimeUnit.SECONDS));
        assertInstanceOf(BusinessException.class, failure.getCause());
        assertEquals(409, ((BusinessException) failure.getCause()).getHttpStatus());
    }

    private void assertImmutable(Runnable operation) {
        RuntimeException failure = assertThrows(RuntimeException.class, operation::run);
        Throwable cause = failure;
        while (!(cause instanceof BusinessException) && cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(BusinessException.class, cause);
        assertEquals(409, ((BusinessException) cause).getHttpStatus());
    }

    private <T> T holdBeforeCommit(Supplier<T> operation, CountDownLatch writesFinished, CountDownLatch allowCommit) {
        return transaction.execute(status -> {
            T result = operation.get();
            writesFinished.countDown();
            try {
                if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return result;
        });
    }

    private SubscriptionResponse register() {
        return service.register(request(1L, UUID.randomUUID().toString()));
    }

    private RegisterSubscriptionRequest request(Long vehicleId, String requestKey) {
        RegisterSubscriptionRequest request = new RegisterSubscriptionRequest();
        request.setVehicleId(vehicleId);
        request.setParkingLotId(1L);
        request.setPackageId(packageId);
        request.setRequestKey(requestKey);
        request.setStartsAt(START);
        request.setPaymentDeadlineAt(DEADLINE);
        return request;
    }

    private PaymentEntity payment(long subscriptionId) {
        return transaction.execute(status -> {
            PaymentEntity intent = new PaymentEntity();
            intent.setInvoiceNo("subscription_" + UUID.randomUUID().toString().replace("-", ""));
            intent.setAmount(BigDecimal.valueOf(service.getById(subscriptionId).getPriceVnd()));
            intent.setCurrency("VND");
            intent.setStatus("PENDING");
            intent.setPaymentMethod("QR");
            intent.setGateway("SEPAY");
            intent.setCallbackType("SUBSCRIPTION");
            JSONObject context = new JSONObject();
            context.set("subscriptionId", subscriptionId);
            intent.setCallbackContext(context);
            return payments.saveAndFlush(intent);
        });
    }

    private VerifiedSubscriptionPayment evidence(long subscriptionId, PaymentEntity payment, String reference, long amount) {
        return new VerifiedSubscriptionPayment(subscriptionId, payment.getId(), "SEPAY", reference,
                payment.getInvoiceNo(), amount, "VND", NOW.minusSeconds(1), NOW, Map.of("testEvidence", true));
    }

    private void awaitDatabaseLock() throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            if (number("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'", schema) > 0) return;
            Thread.sleep(25);
        }
        fail("Competing service callback did not reach PostgreSQL's vehicle lock");
    }

    private Connection openRaw() throws SQLException { return SubscriptionJpaTestSupport.openRaw(); }
    private Connection open() throws SQLException { return SubscriptionJpaTestSupport.open(schema); }

    private long number(String sql, Object... arguments) throws SQLException {
        return number(database, sql, arguments);
    }

    private static long number(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement query = prepared(connection, sql, arguments); ResultSet rows = query.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement query = prepared(connection, sql, arguments)) { query.executeUpdate(); }
    }

    private static PreparedStatement prepared(Connection connection, String sql, Object... arguments) throws SQLException {
        PreparedStatement query = connection.prepareStatement(sql);
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            query.setObject(i + 1, argument instanceof Instant instant ? Timestamp.from(instant) : argument);
        }
        return query;
    }
}
