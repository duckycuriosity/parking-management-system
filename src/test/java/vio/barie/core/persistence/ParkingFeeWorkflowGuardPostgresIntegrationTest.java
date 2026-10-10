package vio.barie.core.persistence;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.ParkingFeeWorkflowGuard;
import vio.barie.core.service.PaymentService;
import vio.barie.core.service.SubscriptionPaymentReportService;
import vio.barie.core.service.payment.common.PaymentGateway;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL transactions serialize new quotes against legacy session invoices. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class ParkingFeeWorkflowGuardPostgresIntegrationTest {
    private String schema;
    private Connection database;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager em;
    private TransactionTemplate transaction;
    private PaymentRepository payments;
    private FeeCalculationRepository calculations;
    private ParkingSessionRepository sessions;
    private ParkingFeeWorkflowGuard guard;
    private PaymentService legacy;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() throws SQLException {
        schema = "fee_workflow_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = ParkingFeeJpaTestSupport.openRaw()) {
            ParkingFeeJpaTestSupport.execute(admin, "CREATE SCHEMA " + schema);
        }
        ParkingFeeJpaTestSupport.createLegacySchemaAndSeed(schema);
        ParkingFeeJpaTestSupport.migrate(schema, "56");
        database = ParkingFeeJpaTestSupport.open(schema);
        factory = ParkingFeeJpaTestSupport.entityManagerFactory(schema, ParkingFeeJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(em);
        payments = repositories.getRepository(PaymentRepository.class);
        calculations = repositories.getRepository(FeeCalculationRepository.class);
        sessions = repositories.getRepository(ParkingSessionRepository.class);
        JpaTransactionManager manager = new JpaTransactionManager(emf);
        transaction = new TransactionTemplate(manager);
        guard = new ParkingFeeWorkflowGuard(payments, calculations, sessions, em);
        PaymentService target = new PaymentService(payments, mock(PaymentGateway.class),
                mock(StringRedisTemplate.class), mock(SubscriptionPaymentReportService.class));
        target.setFeeWorkflowGuard(guard);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        legacy = (PaymentService) proxy.getProxy();
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (factory != null) factory.destroy();
        if (database != null) database.close();
        if (schema != null) try (Connection admin = ParkingFeeJpaTestSupport.openRaw()) {
            ParkingFeeJpaTestSupport.execute(admin, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void oldInvoiceInEveryStatusBlocksNewWorkflowButScopedAndOtherSessionRowsDoNot() {
        for (String status : new String[]{"PENDING", "FAILED", "PAID", "REVIEW_REQUIRED"}) {
            transaction.executeWithoutResult(tx -> {
                PaymentEntity intent = new PaymentEntity(); intent.setInvoiceNo("OLD-" + status);
                intent.setStatus(status); intent.setCallbackType("SESSION");
                intent.setCallbackContext(new JSONObject().set("sessionId", 1L)); payments.saveAndFlush(intent);
            });
            assertTrue(guard.hasLegacyPaymentHistory(1L));
            assertConflict(() -> transaction.executeWithoutResult(tx -> guard.assertNewWorkflowAllowed(1L)));
        }
        transaction.executeWithoutResult(tx -> {
            PaymentEntity scoped = new PaymentEntity(); scoped.setInvoiceNo("NEW-SCOPED");
            scoped.setStatus("PAID"); scoped.setCallbackType("SESSION");
            scoped.setCallbackContext(new JSONObject().set("sessionId", 2L).set("calculationId", 9L));
            payments.saveAndFlush(scoped);
        });
        assertFalse(guard.hasLegacyPaymentHistory(2L));
        transaction.executeWithoutResult(tx -> guard.assertNewWorkflowAllowed(2L));
    }

    @Test
    void anyNewCalculationHistoryBlocksLegacyEvenIfSuperseded() throws SQLException {
        long id = newQuote();
        transaction.executeWithoutResult(tx -> {
            FeeCalculationEntity record = calculations.findById(id).orElseThrow();
            record.setStatus("SUPERSEDED"); em.flush();
        });
        assertConflict(() -> oldPayment());
        assertEquals(0, number("SELECT count(*) FROM payment"));
        assertEquals(1, number("SELECT count(*) FROM fee_calculation"));
    }

    @Test
    void newQuoteWaitsForConcurrentLegacyInvoiceThenRejectsIt() throws Exception {
        race(false);
        assertEquals(1, number("SELECT count(*) FROM payment"));
        assertEquals(0, number("SELECT count(*) FROM fee_calculation"));
    }

    @Test
    void legacyInvoiceWaitsForConcurrentNewQuoteThenRejectsIt() throws Exception {
        race(true);
        assertEquals(0, number("SELECT count(*) FROM payment"));
        assertEquals(1, number("SELECT count(*) FROM fee_calculation"));
    }

    private void race(boolean newFirst) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(1), commit = new CountDownLatch(1);
        try {
            Future<Long> first = workers.submit(() -> hold(newFirst ? this::newQuote : this::oldPayment, written, commit));
            assertTrue(written.await(10, TimeUnit.SECONDS));
            Future<Long> competitor = workers.submit(newFirst ? this::oldPayment : this::newQuote);
            awaitDatabaseLock();
            assertFalse(competitor.isDone());
            commit.countDown(); assertNotNull(first.get(10, TimeUnit.SECONDS));
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> competitor.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, rejected.getCause());
            assertEquals("FEE_WORKFLOW_CONFLICT", ((BusinessException) rejected.getCause()).getCode());
        } finally {
            commit.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private long oldPayment() {
        return legacy.createSessionPayment("OLD-" + UUID.randomUUID(), BigDecimal.valueOf(1000), "QR", "Legacy fee",
                1L, "barrier", "30A12345", null, 1L, LocalDateTime.of(2026,10,9,10,0)).getId();
    }

    /** Mirrors the new service's approved vehicle → session lock order and guard before snapshot insert. */
    private long newQuote() {
        return transaction.execute(tx -> {
            ParkingSessionEntity preview = sessions.findById(1L).orElseThrow();
            em.find(VehicleEntity.class, preview.getVehicle().getId(), LockModeType.PESSIMISTIC_WRITE);
            ParkingSessionEntity session = sessions.lockForFee(1L).orElseThrow(); em.refresh(session);
            guard.assertNewWorkflowAllowed(1L);
            FeeCalculationEntity calculation = new FeeCalculationEntity();
            Instant at = Instant.parse("2026-10-09T02:00:00Z");
            calculation.setSessionId(1L); calculation.setChargeContext("VISITOR"); calculation.setCalculatedAt(at);
            calculation.setCalculationUntil(at); calculation.setValidUntil(at.plusSeconds(600));
            calculation.setCreatedAt(at); calculation.setEngineRevision("workflow-test");
            calculation.setInputFingerprint("0".repeat(64)); calculation.setSubtotalVnd(1000); calculation.setTotalVnd(1000);
            calculation.setFeeDetail(mapper.createObjectNode().put("timezone", "Asia/Ho_Chi_Minh"));
            return calculations.saveAndFlush(calculation).getId();
        });
    }

    private <T> T hold(Supplier<T> action, CountDownLatch written, CountDownLatch commit) {
        return transaction.execute(tx -> {
            T result = action.get(); written.countDown();
            try { assertTrue(commit.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            return result;
        });
    }
    private void assertConflict(org.junit.jupiter.api.function.Executable operation) {
        assertEquals("FEE_WORKFLOW_CONFLICT", assertThrows(BusinessException.class, operation).getCode());
    }
    private void awaitDatabaseLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (number("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'", schema) > 0) return;
            Thread.sleep(10);
        }
        fail("Competitor never waited on the shared PostgreSQL row lock");
    }
    private long number(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        }
    }
}
