package vio.barie.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.ParkingPaymentResponse;
import vio.barie.core.dto.fee.VerifiedParkingPayment;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.FeeCalculationEntity;
import vio.barie.core.entity.ParkingFeePaymentEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.event.ParkingFeeSettled;
import vio.barie.core.repository.FeeCalculationRepository;
import vio.barie.core.repository.ParkingFeePaymentRepository;
import vio.barie.core.repository.ParkingFeeTransactionKeyRepository;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.service.ParkingFeeService;
import vio.barie.core.service.ParkingPaymentService;
import vio.barie.core.service.PaymentService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Actual V52-V56 migrations, current entity mappings, repositories and service transaction advice.
 * Only the gateway link is mocked. The fee preparation seam acquires real vehicle/session/calculation
 * row locks; separate ParkingFeeService tests cover coverage preparation itself. Fixture JDBC is
 * confined to disposable schema setup and independent committed-data observations.
 */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class ParkingPaymentPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");
    private String schema;
    private Connection database;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private TransactionTemplate transaction;
    private FeeCalculationRepository calculations;
    private ParkingPaymentService service;
    private final List<ParkingFeeSettled> events = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void createIsolatedMigratedSchemaAndService() throws SQLException {
        schema = "parking_payment_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = ParkingFeeJpaTestSupport.openRaw()) {
            ParkingFeeJpaTestSupport.execute(administration, "CREATE SCHEMA " + schema);
        }
        ParkingFeeJpaTestSupport.createLegacySchemaAndSeed(schema);
        ParkingFeeJpaTestSupport.migrate(schema, "56");
        database = ParkingFeeJpaTestSupport.open(schema);
        factory = ParkingFeeJpaTestSupport.entityManagerFactory(schema, ParkingFeeJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
        repositories.addRepositoryProxyPostProcessor((proxy, metadata) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        calculations = repositories.getRepository(FeeCalculationRepository.class);
        JpaTransactionManager manager = new JpaTransactionManager(emf);
        transaction = new TransactionTemplate(manager);

        ParkingFeeService fees = mock(ParkingFeeService.class);
        when(fees.lockForSettlement(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            FeeCalculationEntity preview = calculations.findById(id).orElseThrow();
            ParkingSessionEntity session = entityManager.find(ParkingSessionEntity.class, preview.getSessionId());
            entityManager.lock(session.getVehicle(), LockModeType.PESSIMISTIC_WRITE);
            entityManager.lock(session, LockModeType.PESSIMISTIC_WRITE);
            entityManager.refresh(session);
            FeeCalculationEntity result = calculations.lockById(id).orElseThrow();
            entityManager.refresh(result);
            return result;
        });
        PaymentService gateway = mock(PaymentService.class);
        AtomicInteger invoices = new AtomicInteger();
        when(gateway.createPaymentLink(any())).thenAnswer(call -> {
            CreatePaymentResponse result = new CreatePaymentResponse();
            result.setResultCd("00_000"); result.setInvoiceNo("PG-FEE-" + invoices.incrementAndGet());
            result.setAmount(call.<vio.barie.core.dto.payment.CreatePaymentRequest>getArgument(0).getAmount());
            result.setPaymentLink("https://example.test/payment"); result.setPayType("QR");
            return result;
        });
        ParkingPaymentService target = new ParkingPaymentService(fees, gateway,
                repositories.getRepository(PaymentRepository.class),
                repositories.getRepository(ParkingFeePaymentRepository.class),
                repositories.getRepository(ParkingFeeTransactionKeyRepository.class),
                entityManager, mapper, Clock.fixed(NOW, ZoneOffset.UTC), event -> {
                    ParkingFeeSettled settled = (ParkingFeeSettled) event;
                    try {
                        // A separate JDBC connection sees the result only after commit.
                        assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE id = ? AND status = 'SETTLED'",
                                settled.calculationId()));
                    } catch (SQLException ex) { throw new IllegalStateException(ex); }
                    events.add(settled);
                }, "SEPAY");
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (ParkingPaymentService) proxy.getProxy();
    }

    @AfterEach
    void discardOnlyThisSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (database != null) database.close();
        if (schema != null) {
            try (Connection administration = ParkingFeeJpaTestSupport.openRaw()) {
                ParkingFeeJpaTestSupport.execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void requestReplayTransactionReplayAndAdditionalTransferPreserveActualEvidence() throws SQLException {
        long calculation = calculation(1, 12000);
        ParkingPaymentResponse intent = service.createPaymentRequest(calculation, "request-one");
        assertEquals(intent.paymentId(), service.createPaymentRequest(calculation, "request-one").paymentId());
        assertEquals(1, number("SELECT count(*) FROM payment"));
        assertEquals(1, number("SELECT count(*) FROM parking_fee_payment"));
        VerifiedParkingPayment first = evidence(intent, "first", 12000);
        assertEquals("APPLIED", service.confirmVerifiedPayment(first).applicationStatus());
        assertEquals("APPLIED", service.confirmVerifiedPayment(first).applicationStatus());
        ParkingPaymentResponse extra = service.confirmVerifiedPayment(evidence(intent, "second", 5000));
        assertEquals("REVIEW_REQUIRED", extra.applicationStatus());
        assertEquals("ADDITIONAL_TRANSFER", extra.applicationReason());
        assertEquals(2, number("SELECT count(*) FROM parking_fee_transaction_key"));
        assertEquals(2, number("SELECT jsonb_array_length(receipt) FROM parking_fee_payment WHERE id = ?", intent.attemptId()));
        assertEquals(17000, number("SELECT sum((item->>'amountVnd')::bigint) FROM parking_fee_payment p, jsonb_array_elements(p.receipt) item"));
        assertEquals(1, number("SELECT count(*) FROM payment WHERE status = 'PAID' AND reference_code = 'first'"));
        assertEquals(0, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, events.size());
    }

    @Test
    void failureDuringFinalFlushRollsBackRegistryEvidenceAndCalculationThenAllowsRetry() throws SQLException {
        long calculation = calculation(1, 12000);
        ParkingPaymentResponse intent = service.createPaymentRequest(calculation, "rollback-request");
        ParkingFeeJpaTestSupport.execute(database,
                "ALTER TABLE payment ADD CONSTRAINT test_reject_paid CHECK (status <> 'PAID')");
        assertThrows(RuntimeException.class, () -> service.confirmVerifiedPayment(evidence(intent, "rollback", 12000)));
        assertEquals(0, number("SELECT count(*) FROM parking_fee_transaction_key"));
        assertEquals(0, number("SELECT jsonb_array_length(receipt) FROM parking_fee_payment WHERE id = ?", intent.attemptId()));
        assertEquals(1, number("SELECT count(*) FROM parking_fee_payment WHERE status = 'PENDING'"));
        assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE status = 'QUOTED'"));
        assertEquals(0, events.size());
        ParkingFeeJpaTestSupport.execute(database, "ALTER TABLE payment DROP CONSTRAINT test_reject_paid");
        assertEquals("APPLIED", service.confirmVerifiedPayment(evidence(intent, "rollback", 12000)).applicationStatus());
        assertEquals(1, number("SELECT count(*) FROM parking_fee_transaction_key"));
        assertEquals(1, events.size());
    }

    @Test
    void amountMismatchCommitsActualMoneyForReviewWithoutSettlingCalculation() throws SQLException {
        ParkingPaymentResponse intent = service.createPaymentRequest(calculation(1, 12000), "mismatch-request");
        ParkingPaymentResponse result = service.confirmVerifiedPayment(evidence(intent, "partial", 11000));
        assertEquals("REVIEW_REQUIRED", result.status());
        assertEquals("AMOUNT_MISMATCH", result.applicationReason());
        assertEquals(11000, number("SELECT (receipt->0->>'amountVnd')::bigint FROM parking_fee_payment WHERE id = ?", intent.attemptId()));
        assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE status = 'QUOTED'"));
        assertEquals(1, number("SELECT count(*) FROM payment WHERE status = 'REVIEW_REQUIRED' AND amount = 12000"));
        assertEquals(0, events.size());
    }

    @Test
    void fullyCoveredCalculationSettlesOnceWithoutInventingMoneyRows() throws SQLException {
        long calculation = calculation(1, 0);
        assertEquals("NO_CHARGE", service.settleNoCharge(calculation).status());
        assertEquals("NO_CHARGE", service.settleNoCharge(calculation).status());
        assertEquals(0, number("SELECT count(*) FROM payment"));
        assertEquals(0, number("SELECT count(*) FROM parking_fee_payment"));
        assertEquals(0, number("SELECT count(*) FROM parking_fee_transaction_key"));
        assertEquals(1, events.size());
        assertNull(events.getFirst().paymentId());
    }

    @Test
    void populatedLegacyUuidSessionCannotBeSilentlyConvertedOrDeletedByMigration() throws SQLException {
        String legacySchema = "parking_uuid_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = ParkingFeeJpaTestSupport.openRaw()) {
            ParkingFeeJpaTestSupport.execute(administration, "CREATE SCHEMA " + legacySchema);
        }
        try {
            ParkingFeeJpaTestSupport.createLegacySchemaAndSeed(legacySchema);
            ParkingFeeJpaTestSupport.migrate(legacySchema, "55");
            try (Connection legacy = ParkingFeeJpaTestSupport.open(legacySchema)) {
                ParkingFeeJpaTestSupport.execute(legacy, """
                        INSERT INTO fee_calculation
                            (session_id, charge_context, calculated_at, calculation_until, engine_revision,
                             input_fingerprint, subtotal_vnd, rounding_adjustment_vnd, total_vnd, fee_detail)
                        VALUES ('00000000-0000-0000-0000-000000000001', 'VISITOR', now(), now(),
                                'legacy', 'legacy-input', 12000, 0, 12000, '{}'::jsonb)
                        """);
                FlywayException rejected = assertThrows(FlywayException.class,
                        () -> ParkingFeeJpaTestSupport.migrate(legacySchema, "56"));
                assertTrue(rejected.getMessage().contains("UUID session IDs"), rejected::getMessage);
                try (PreparedStatement query = legacy.prepareStatement("SELECT session_id::text, total_vnd FROM fee_calculation");
                     ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("00000000-0000-0000-0000-000000000001", row.getString(1));
                    assertEquals(12000, row.getLong(2));
                    assertFalse(row.next());
                }
            }
        } finally {
            try (Connection administration = ParkingFeeJpaTestSupport.openRaw()) {
                ParkingFeeJpaTestSupport.execute(administration, "DROP SCHEMA IF EXISTS " + legacySchema + " CASCADE");
            }
        }
    }

    @Test
    void editingSettledPriceOrSnapshotThroughJpaRollsBackAndPreservesHistoricalInputs() throws SQLException {
        long calculation = calculation(1, 12000);
        ParkingPaymentResponse intent = service.createPaymentRequest(calculation, "immutable-snapshot");
        service.confirmVerifiedPayment(evidence(intent, "snapshot-paid", 12000));

        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            FeeCalculationEntity persisted = calculations.lockById(calculation).orElseThrow();
            persisted.setTotalVnd(99000);
            persisted.setFeeDetail(mapper.createObjectNode().put("timezone", "UTC"));
            entityManager.flush();
        }));

        assertEquals(1, number("""
                SELECT count(*) FROM fee_calculation WHERE id = ? AND status = 'SETTLED'
                    AND total_vnd = 12000 AND fee_detail->>'timezone' = 'Asia/Ho_Chi_Minh'
                """, calculation));
        assertEquals(1, events.size());
    }

    @Test
    void overwritingVerifiedReceiptThroughJpaRollsBackAndPreservesActualMoneyEvidence() throws SQLException {
        ParkingPaymentResponse intent = service.createPaymentRequest(calculation(1, 12000), "immutable-receipt");
        service.confirmVerifiedPayment(evidence(intent, "receipt-paid", 12000));

        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            ParkingFeePaymentEntity persisted = entityManager.find(ParkingFeePaymentEntity.class, intent.attemptId());
            persisted.setReceipt(mapper.createArrayNode());
            entityManager.flush();
        }));

        assertEquals(1, number("""
                SELECT count(*) FROM parking_fee_payment WHERE id = ? AND status = 'PAID'
                    AND jsonb_array_length(receipt) = 1 AND receipt->0->>'reference' = 'receipt-paid'
                    AND (receipt->0->>'amountVnd')::bigint = 12000
                """, intent.attemptId()));
        assertEquals(1, number("SELECT count(*) FROM parking_fee_transaction_key"));
        assertEquals(1, events.size());
    }

    @Test
    void twoCallbacksWaitOnRealRowsAndOnlyOneSettles() throws Exception {
        ParkingPaymentResponse intent = service.createPaymentRequest(calculation(1, 12000), "concurrent-request");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var winner = workers.submit(() -> holdBeforeCommit(
                    () -> service.confirmVerifiedPayment(evidence(intent, "winner", 12000)), written, commit));
            assertTrue(written.await(5, TimeUnit.SECONDS));
            var loser = workers.submit(() -> service.confirmVerifiedPayment(evidence(intent, "extra", 12000)));
            try { awaitDatabaseLock(); } finally { commit.countDown(); }
            assertEquals("APPLIED", winner.get(10, TimeUnit.SECONDS).applicationStatus());
            assertEquals("ADDITIONAL_TRANSFER", loser.get(10, TimeUnit.SECONDS).applicationReason());
            assertEquals(2, number("SELECT jsonb_array_length(receipt) FROM parking_fee_payment"));
            assertEquals(2, number("SELECT count(*) FROM parking_fee_transaction_key"));
            assertEquals(1, events.size());
        } finally {
            commit.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSameRequestKeyCreatesOneIntentAndReturnsTheCommittedBinding() throws Exception {
        long calculation = calculation(1, 12000);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var creator = workers.submit(() -> holdBeforeCommit(
                    () -> service.createPaymentRequest(calculation, "concurrent-key"), written, commit));
            assertTrue(written.await(5, TimeUnit.SECONDS));
            var retry = workers.submit(() -> service.createPaymentRequest(calculation, "concurrent-key"));
            try { awaitDatabaseLock(); } finally { commit.countDown(); }
            assertEquals(creator.get(10, TimeUnit.SECONDS).paymentId(), retry.get(10, TimeUnit.SECONDS).paymentId());
            assertEquals(1, number("SELECT count(*) FROM payment"));
            assertEquals(1, number("SELECT count(*) FROM parking_fee_payment"));
            assertEquals(0, number("SELECT count(*) FROM parking_fee_transaction_key"));
            assertEquals(0, events.size());
        } finally {
            commit.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void globalReferenceRaceAcrossTwoVehiclesCannotReassignTheWinningTransaction() throws Exception {
        ParkingPaymentResponse first = service.createPaymentRequest(calculation(1, 12000), "first-request");
        ParkingPaymentResponse second = service.createPaymentRequest(calculation(2, 12000), "second-request");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var winner = workers.submit(() -> holdBeforeCommit(
                    () -> service.confirmVerifiedPayment(evidence(first, "global-reference", 12000)), written, commit));
            assertTrue(written.await(5, TimeUnit.SECONDS));
            var loser = workers.submit(() -> service.confirmVerifiedPayment(evidence(second, "global-reference", 12000)));
            try { awaitDatabaseLock(); } finally { commit.countDown(); }
            assertEquals("APPLIED", winner.get(10, TimeUnit.SECONDS).applicationStatus());
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> loser.get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof BusinessException
                    || rejected.getCause() instanceof DataIntegrityViolationException
                    || rejected.getCause() instanceof jakarta.persistence.PersistenceException,
                    () -> "Unexpected reference conflict: " + rejected.getCause());
            assertEquals(first.attemptId().longValue(), number("SELECT attempt_id FROM parking_fee_transaction_key WHERE reference = 'global-reference'"));
            assertEquals(1, number("SELECT count(*) FROM parking_fee_transaction_key"));
            assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE status = 'SETTLED'"));
            assertEquals(0, number("SELECT jsonb_array_length(receipt) FROM parking_fee_payment WHERE id = ?", second.attemptId()));
            assertEquals(1, events.size());
        } finally {
            commit.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private long calculation(long sessionId, long amount) {
        return transaction.execute(status -> {
            FeeCalculationEntity calculation = new FeeCalculationEntity();
            calculation.setSessionId(sessionId); calculation.setChargeContext("VISITOR");
            calculation.setCalculatedAt(NOW); calculation.setCalculationUntil(NOW);
            calculation.setValidUntil(NOW.plusSeconds(600)); calculation.setEngineRevision("test-engine");
            calculation.setInputFingerprint(String.format("%064d", sessionId)); calculation.setSubtotalVnd(amount);
            calculation.setTotalVnd(amount); calculation.setCreatedAt(NOW);
            ParkingSessionEntity session = entityManager.find(ParkingSessionEntity.class, sessionId);
            calculation.setFeeDetail(mapper.createObjectNode().put("timezone", "Asia/Ho_Chi_Minh")
                    .put("sessionId", sessionId).put("vehicleId", session.getVehicle().getId())
                    .put("parkingLotId", session.getParkingLot().getId()));
            return calculations.saveAndFlush(calculation).getId();
        });
    }

    private VerifiedParkingPayment evidence(ParkingPaymentResponse intent, String reference, long amount) {
        return new VerifiedParkingPayment(intent.paymentId(), "SEPAY", reference, intent.invoiceNo(), amount,
                "VND", NOW.plusSeconds(5), NOW.plusSeconds(10), "PAID", Map.of("fixture", "postgres"));
    }

    private <T> T holdBeforeCommit(Supplier<T> action, CountDownLatch written, CountDownLatch commit) {
        return transaction.execute(status -> {
            T result = action.get(); written.countDown();
            try { assertTrue(commit.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            return result;
        });
    }

    private void awaitDatabaseLock() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            if (number("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'", schema) > 0) return;
            Thread.sleep(10);
        }
        fail("Competing PostgreSQL transaction never waited on an actual row/unique lock");
    }

    private long number(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }
}
