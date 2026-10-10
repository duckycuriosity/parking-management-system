package vio.barie.core.persistence;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.application.ParkingFeeSettings;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.*;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.subscription.*;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.*;
import vio.barie.core.utils.VehicleType;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Real JPA orchestration, PostgreSQL locks, V52-V56 schema and immutable JSON snapshots. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class ParkingFeeServicePostgresIntegrationTest {
    private static final Instant ENTRY = Instant.parse("2026-10-09T01:00:00Z");
    private static final Instant NOW = ENTRY.plusSeconds(3600);
    private static final Instant DEADLINE = NOW.plusSeconds(3600);
    private String schema;
    private Connection database;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager em;
    private JpaTransactionManager manager;
    private TransactionTemplate transaction;
    private FeeCalculationRepository calculations;
    private PaymentRepository payments;
    private VehicleSubscriptionRepository subscriptionRepository;
    private ParkingSessionRepository sessions;
    private FeePolicyService policies;
    private VehicleSubscriptionService subscriptions;
    private ParkingFeeService service;
    private ParkingFeeSettings settings;
    private Clock clock;
    private Integer policyId;

    @BeforeEach
    void isolatedPostgresWithRealTransactionAdvice() throws SQLException {
        schema = "parking_fee_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = ParkingFeeJpaTestSupport.openRaw()) {
            ParkingFeeJpaTestSupport.execute(admin, "CREATE SCHEMA " + schema);
        }
        ParkingFeeJpaTestSupport.createLegacySchemaAndSeed(schema);
        ParkingFeeJpaTestSupport.migrate(schema, "56");
        database = ParkingFeeJpaTestSupport.open(schema);
        policyId = Math.toIntExact(number("""
                INSERT INTO fee_policy (parking_lot_id, vehicle_type, name, version_no,
                    effective_from, calculation_method, daily_price_vnd, pricing_rules)
                VALUES (1, 'CAR', 'Entry daily price', 1, ?, 'DAILY_ENTRY', 5000, '{}'::jsonb) RETURNING id
                """, ENTRY.minusSeconds(86400)));
        factory = ParkingFeeJpaTestSupport.entityManagerFactory(schema, ParkingFeeJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject(); assertNotNull(emf);
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(em);
        repositories.addRepositoryProxyPostProcessor((proxy, metadata) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        calculations = repositories.getRepository(FeeCalculationRepository.class);
        payments = repositories.getRepository(PaymentRepository.class);
        subscriptionRepository = repositories.getRepository(VehicleSubscriptionRepository.class);
        sessions = repositories.getRepository(ParkingSessionRepository.class);
        manager = new JpaTransactionManager(emf); transaction = new TransactionTemplate(manager);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        subscriptions = proxy(new VehicleSubscriptionService(subscriptionRepository,
                repositories.getRepository(SubscriptionPackageRepository.class),
                repositories.getRepository(VehicleSubscriptionReceiptRepository.class),
                payments, clock, em, event -> { }));
        policies = proxy(new FeePolicyService(repositories.getRepository(FeePolicyRepository.class), clock));
        settings = new ParkingFeeSettings();
        settings.setSessionTimeZone("Asia/Ho_Chi_Minh");
        settings.getParkingLotTimeZones().put(1L, "Asia/Ho_Chi_Minh");
        settings.getParkingLotTimeZones().put(2L, "Asia/Ho_Chi_Minh");
        settings.setOutsidePricing(ParkingFeeSettings.OutsidePricing.ENTRY_VERSION);
        settings.setEntitlementTiming(ParkingFeeSettings.EntitlementTiming.PERIOD_START);
        settings.setDailyGrouping(FeeRules.DailyGrouping.SESSION_DAY);
        settings.setDailyPriceSelection(FeeRules.DailyPriceSelection.FIRST);
        service = feeService(sessions);
    }

    private ParkingFeeService feeService(ParkingSessionRepository repository) {
        return proxy(new ParkingFeeService(repository, calculations, subscriptionRepository, subscriptions,
                policies, new SubscriptionCoverageCalculator(),
                new FeeCalculator(new TimeBlockCalculator(), new DailyEntryCalculator()), settings,
                new ParkingFeeWorkflowGuard(payments, calculations, sessions, em), em,
                clock, new ObjectMapper().findAndRegisterModules()));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @AfterEach
    void discardOnlyThisDisposableSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (database != null) database.close();
        if (schema != null) try (Connection admin = ParkingFeeJpaTestSupport.openRaw()) {
            ParkingFeeJpaTestSupport.execute(admin, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void displayOnlySimulationCreatesNeitherQuotesNorPaymentAndLeavesSessionUntouched() throws SQLException {
        ParkingFeeResponse display = service.simulate(new ParkingFeeQueryRequest(1L, ENTRY.plusSeconds(1800)));
        assertEquals(5000, display.totalVnd()); assertNull(display.calculationId());
        assertEquals(0, number("SELECT count(*) FROM fee_calculation"));
        assertEquals(0, number("SELECT count(*) FROM payment"));
        assertEquals(0, number("SELECT count(*) FROM parking_fee_payment"));
        assertEquals(0, number("SELECT count(*) FROM parking_session WHERE exit_time IS NOT NULL"));
    }

    @Test
    void quotesUseBackendCutoffAndAppendHistoryWithoutEditingEarlierSnapshots() throws SQLException {
        ParkingFeeResponse first = quote(); ParkingFeeResponse second = quote();
        assertEquals(NOW, second.calculationUntil()); assertEquals(DEADLINE, second.validUntil());
        ParkingFeeResponse historical = service.getCalculation(first.calculationId());
        assertEquals("SUPERSEDED", historical.status()); assertSnapshotEquals(first.snapshot(), historical.snapshot());
        assertEquals(first.inputFingerprint(), historical.inputFingerprint());
        assertEquals(2, service.history(1L).size());
        assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE status = 'QUOTED'"));
        assertThrows(RuntimeException.class, () -> transaction.execute(status -> {
            FeeCalculationEntity row = calculations.findById(first.calculationId()).orElseThrow();
            row.setTotalVnd(6000); calculations.flush(); return null;
        }));
        assertEquals(first.totalVnd(), service.getCalculation(first.calculationId()).totalVnd());
    }

    @Test
    void closingTheVisitorTariffAfterQuotingKeepsFrozenPriceValidForSettlement() throws SQLException {
        ParkingFeeResponse quoted = quote();
        FeePolicyApplyRequest next = new FeePolicyApplyRequest(); next.setParkingLotId(1L);
        next.setVehicleType(VehicleType.CAR); next.setName("Later expensive daily price");
        next.setCalculationMethod(FeeCalculationMethod.DAILY_ENTRY); next.setDailyPriceVnd(20000L);
        next.setEffectiveFrom(NOW.plusSeconds(3600));
        policies.apply(next);
        assertEquals(1, number("SELECT count(*) FROM fee_policy WHERE id = ? AND effective_to IS NOT NULL", policyId));
        FeeCalculationEntity prepared = transaction.execute(status -> service.lockForSettlement(quoted.calculationId()));
        assertNotNull(prepared); assertEquals("QUOTED", prepared.getStatus()); assertEquals(5000, prepared.getTotalVnd());
        assertTrue(prepared.getFeeDetail().get("allocations").get(0).get("policy").get("effectiveTo").isNull());
        assertSnapshotEquals(quoted.snapshot(), service.getCalculation(quoted.calculationId()).snapshot());
    }

    @Test
    void newlyPaidCoverageSupersedesTheOldQuoteAndCreatesZeroFeeWithoutAnotherPriceLookup() throws SQLException {
        ParkingFeeResponse before = quote(); SubscriptionResponse order = register();
        confirm(order);
        transaction.execute(status -> {
            assertEquals("SUPERSEDED", service.lockForSettlement(before.calculationId()).getStatus()); return null;
        });
        ParkingFeeResponse after = quote(); assertEquals(0, after.totalVnd());
        assertTrue(after.snapshot().get("coverage").get("uncovered").isEmpty());
        assertTrue(after.snapshot().get("allocations").isEmpty());
        assertEquals(5000, service.getCalculation(before.calculationId()).totalVnd());
        assertEquals(1, number("SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE id = ? AND fee_policy_id IS NULL", after.calculationId()));
    }

    @Test
    void settlementRefreshesAFirstLevelCachedPendingPeriodAfterItsCallbackCommits() throws Exception {
        ParkingFeeResponse quote = quote(); SubscriptionResponse order = register();
        PaymentEntity intent = payment(order);
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            transaction.execute(status -> {
                VehicleSubscriptionEntity cached = subscriptionRepository.findById(order.getId()).orElseThrow();
                assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, cached.getStatus());
                try { worker.submit(() -> subscriptions.confirmVerifiedPayment(evidence(order, intent)))
                        .get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, cached.getStatus());
                assertEquals("SUPERSEDED", service.lockForSettlement(quote.calculationId()).getStatus());
                assertEquals(VehicleSubscriptionStatus.PAID, cached.getStatus());
                return null;
            });
        }
        assertEquals("SUPERSEDED", service.getCalculation(quote.calculationId()).status());
    }

    @Test
    void settledSnapshotsStayHistoricalAfterNewEntitlementsAndRejectFurtherCollection() throws SQLException {
        ParkingFeeResponse quote = quote();
        transaction.execute(status -> {
            FeeCalculationEntity row = service.lockForSettlement(quote.calculationId());
            row.setStatus("SETTLED"); row.setSettledAt(NOW); calculations.flush(); return null;
        });
        confirm(register());
        assertEquals("SETTLED", transaction.execute(status -> service.lockForSettlement(quote.calculationId()).getStatus()));
        assertEquals("FEE_SESSION_ALREADY_SETTLED", assertThrows(BusinessException.class, this::quote).getCode());
        assertSnapshotEquals(quote.snapshot(), service.getCalculation(quote.calculationId()).snapshot());
        assertEquals(1, number("SELECT count(*) FROM fee_calculation"));
        assertThrows(RuntimeException.class, () -> transaction.execute(status -> {
            calculations.findById(quote.calculationId()).orElseThrow().setStatus("QUOTED");
            calculations.flush(); return null;
        }));
        assertEquals("SETTLED", service.getCalculation(quote.calculationId()).status());
    }

    @Test
    void scopeReassignmentBetweenInitialReadAndLockCannotLockTheWrongVehicle() throws SQLException {
        ParkingSessionRepository instrumented = mock(ParkingSessionRepository.class, delegatesTo(sessions));
        doAnswer(call -> {
            var old = sessions.findById(call.getArgument(0));
            // Independent writer runs before checkout owns the session row lock.
            ParkingFeeJpaTestSupport.execute(database, "UPDATE parking_session SET vehicle_id = 2 WHERE id = 1");
            return old;
        }).when(instrumented).findById(anyLong());
        ParkingFeeService guarded = feeService(instrumented);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> guarded.quote(new ParkingFeeQuoteRequest(1L, DEADLINE)));
        assertTrue(exception.getMessage().contains("scope changed"));
        assertEquals(0, number("SELECT count(*) FROM fee_calculation"));
    }

    @Test
    void changedSessionScopeInvalidatesSettlementWithoutRewritingHistoricalInputs() throws SQLException {
        ParkingFeeResponse quoted = quote();
        ParkingFeeJpaTestSupport.execute(database, "UPDATE parking_session SET vehicle_id = 2 WHERE id = 1");
        transaction.execute(status -> {
            assertEquals("SUPERSEDED", service.lockForSettlement(quoted.calculationId()).getStatus()); return null;
        });
        ParkingFeeResponse retained = service.getCalculation(quoted.calculationId());
        assertEquals("SUPERSEDED", retained.status()); assertSnapshotEquals(quoted.snapshot(), retained.snapshot());
        assertEquals(quoted.totalVnd(), retained.totalVnd());
    }

    @Test
    void concurrentQuotesSerializeOnTheVehicleAndLeaveOneCurrentPayableSnapshot() throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1), allowCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ParkingFeeResponse> first = workers.submit(() -> hold( this::quote, firstWritten, allowCommit));
            assertTrue(firstWritten.await(10, TimeUnit.SECONDS));
            Future<ParkingFeeResponse> second = workers.submit(this::quote);
            awaitDatabaseLock(); assertFalse(second.isDone());
            allowCommit.countDown();
            ParkingFeeResponse left = first.get(10, TimeUnit.SECONDS), right = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(left.calculationId(), right.calculationId());
            assertEquals("SUPERSEDED", service.getCalculation(left.calculationId()).status());
            assertEquals("QUOTED", service.getCalculation(right.calculationId()).status());
            assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE status = 'QUOTED'"));
        } finally { allowCommit.countDown(); workers.shutdownNow(); }
    }

    @Test
    void subscriptionConfirmationAndCheckoutUseTheSameVehicleLockThenReadCommittedCoverage() throws Exception {
        ParkingFeeResponse earlier = quote(); SubscriptionResponse order = register(); PaymentEntity intent = payment(order);
        CountDownLatch paidWritten = new CountDownLatch(1), allowCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SubscriptionResponse> payment = workers.submit(() -> hold(
                    () -> subscriptions.confirmVerifiedPayment(evidence(order, intent)), paidWritten, allowCommit));
            assertTrue(paidWritten.await(10, TimeUnit.SECONDS));
            Future<ParkingFeeResponse> checkout = workers.submit(this::quote);
            awaitDatabaseLock(); assertFalse(checkout.isDone());
            allowCommit.countDown(); assertEquals(VehicleSubscriptionStatus.PAID, payment.get(10, TimeUnit.SECONDS).getStatus());
            assertEquals(0, checkout.get(10, TimeUnit.SECONDS).totalVnd());
            assertEquals("SUPERSEDED", service.getCalculation(earlier.calculationId()).status());
            assertEquals(1, number("SELECT count(*) FROM fee_calculation WHERE status = 'QUOTED' AND total_vnd = 0"));
        } finally { allowCommit.countDown(); workers.shutdownNow(); }
    }

    @Test
    void settlementHookCannotEscapeTheCallersWriteTransaction() {
        ParkingFeeResponse quote = quote();
        assertThrows(IllegalTransactionStateException.class, () -> service.lockForSettlement(quote.calculationId()));
        TransactionTemplate reader = new TransactionTemplate(manager); reader.setReadOnly(true);
        assertThrows(BusinessException.class, () -> reader.execute(status -> service.lockForSettlement(quote.calculationId())));
        assertEquals("QUOTED", service.getCalculation(quote.calculationId()).status());
    }

    private ParkingFeeResponse quote() { return service.quote(new ParkingFeeQuoteRequest(1L, DEADLINE)); }

    private SubscriptionResponse register() throws SQLException {
        long packageId = number("""
                INSERT INTO subscription_package (package_code, name, version_no, parking_lot_id, vehicle_type,
                    price_vnd, duration_days, coverage_mode, effective_from, status)
                VALUES ('PKG-' || gen_random_uuid(), 'All-day test package', 1, 1, 'CAR', 300000,
                    30, 'ALL_DAY', ?, 'PUBLISHED') RETURNING id
                """, ENTRY.minusSeconds(86400));
        RegisterSubscriptionRequest request = new RegisterSubscriptionRequest(); request.setVehicleId(1L);
        request.setParkingLotId(1L); request.setPackageId(packageId); request.setRequestKey(UUID.randomUUID().toString());
        request.setStartsAt(ENTRY.minusSeconds(86400)); request.setPaymentDeadlineAt(DEADLINE);
        return subscriptions.register(request);
    }

    private PaymentEntity payment(SubscriptionResponse order) {
        return transaction.execute(status -> {
            PaymentEntity intent = new PaymentEntity(); intent.setInvoiceNo("FEE-SUB-" + UUID.randomUUID());
            intent.setAmount(BigDecimal.valueOf(order.getPriceVnd())); intent.setCurrency("VND");
            intent.setStatus("PENDING"); intent.setPaymentMethod("QR"); intent.setGateway("SEPAY");
            intent.setCallbackType("SUBSCRIPTION");
            JSONObject context = new JSONObject(); context.set("subscriptionId", order.getId()); intent.setCallbackContext(context);
            return payments.saveAndFlush(intent);
        });
    }

    private VerifiedSubscriptionPayment evidence(SubscriptionResponse order, PaymentEntity intent) {
        return new VerifiedSubscriptionPayment(order.getId(), intent.getId(), "SEPAY", "TX-" + order.getId(),
                intent.getInvoiceNo(), order.getPriceVnd(), "VND", NOW.minusSeconds(1), NOW, Map.of());
    }

    private SubscriptionResponse confirm(SubscriptionResponse order) {
        return subscriptions.confirmVerifiedPayment(evidence(order, payment(order)));
    }

    private <T> T hold(Supplier<T> operation, CountDownLatch written, CountDownLatch commit) {
        return transaction.execute(status -> {
            T result = operation.get(); em.flush(); written.countDown();
            try { assertTrue(commit.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            return result;
        });
    }

    private void awaitDatabaseLock() throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            if (number("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'", schema) > 0) return;
            Thread.sleep(25);
        }
        fail("Competing checkout did not wait on PostgreSQL's vehicle lock");
    }

    private long number(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                Object arg = args[index];
                if (arg instanceof Instant value) statement.setTimestamp(index + 1, Timestamp.from(value));
                else statement.setObject(index + 1, arg);
            }
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private static void assertSnapshotEquals(JsonNode before, JsonNode after) {
        // PostgreSQL JSONB normalizes field ordering; Jackson may load small BIGINT
        // values as IntNode after storage although the original DTO used LongNode.
        // Compare complete JSON values, preserving arrays and every financial input.
        ObjectMapper json = new ObjectMapper();
        assertEquals(assertDoesNotThrow(() -> json.readTree(before.toString())),
                assertDoesNotThrow(() -> json.readTree(after.toString())));
    }
}
