package vio.barie.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.entity.VehicleSubscriptionReceiptEntity;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.repository.VehicleSubscriptionReceiptRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.utils.VehicleType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL checks for V54, not a mocked database or an application-context test.
 * Each test owns a random schema in the explicitly supplied disposable test database.
 * No application YAML, production datasource, payment gateway or IoT configuration is loaded.
 */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class VehicleSubscriptionPostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant PAID_AT = Instant.parse("2026-09-30T00:00:00Z");
    private String schema;
    private Connection database;

    @BeforeEach
    void migrateAnIsolatedSchema() throws SQLException {
        schema = "subscription_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection administration = openRaw()) {
            execute(administration, "CREATE SCHEMA " + schema);
        }
        database = open();
        // Preserve explicit target54 coverage of the historical immutable migration.
        // Real legacy mappings are needed by repository methods now expressed in pure JPQL.
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "54");
    }

    @AfterEach
    void discardOnlyThisTestSchema() throws SQLException {
        if (database != null) database.close();
        if (schema != null) {
            try (Connection administration = openRaw()) {
                execute(administration, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void publishedPackagePriceAndScheduleAreFrozenButSalesCanBeClosed() throws SQLException {
        long packageId = draftPackage("OFFICE", 1, "WEEKLY", START, null);
        schedule(packageId, 1, "08:00", "17:00");
        publish(packageId);

        assertSqlState("P0001", () -> execute(database,
                "UPDATE subscription_package SET price_vnd = 700000 WHERE id = ?", packageId));
        assertSqlState("P0001", () -> execute(database,
                "UPDATE subscription_package_schedule SET end_time = TIME '18:00' WHERE package_id = ?", packageId));
        assertSqlState("P0001", () -> schedule(packageId, 2, "08:00", "17:00"));
        assertSqlState("P0001", () -> execute(database,
                "DELETE FROM subscription_package WHERE id = ?", packageId));

        execute(database, "UPDATE subscription_package SET sale_enabled = FALSE, effective_to = ? WHERE id = ?",
                START.plusSeconds(86400), packageId);
        assertEquals(500000, number(database, "SELECT price_vnd FROM subscription_package WHERE id = ?", packageId));
        assertEquals(1, number(database, "SELECT count(*) FROM subscription_package_schedule WHERE package_id = ?", packageId));
    }

    @Test
    void publicationValidatesWeeklyWindowsIncludingSundayWrap() throws SQLException {
        long empty = draftPackage("EMPTY", 1, "WEEKLY", START, null);
        assertSqlState("P0001", () -> publish(empty));

        long wrapping = draftPackage("WRAP", 1, "WEEKLY", START, null);
        schedule(wrapping, 7, "22:00", "02:00");
        schedule(wrapping, 1, "01:00", "03:00");
        assertSqlState("P0001", () -> publish(wrapping));

        long adjacent = draftPackage("ADJACENT", 1, "WEEKLY", START, null);
        schedule(adjacent, 7, "22:00", "02:00");
        schedule(adjacent, 1, "02:00", "08:00");
        publish(adjacent);

        long fullDay = draftPackage("FULL_DAY", 1, "WEEKLY", START, null);
        schedule(fullDay, 1, "08:00", "08:00");
        schedule(fullDay, 2, "08:00", "09:00");
        publish(fullDay); // Equal start/end means exactly 24 hours; adjacency remains valid.

        long allDay = draftPackage("BAD_ALL_DAY", 1, "ALL_DAY", START, null);
        schedule(allDay, 1, "08:00", "09:00");
        assertSqlState("P0001", () -> publish(allDay));
        assertSqlState("23514", () -> schedule(empty, 0, "08:00", "09:00"));
        assertSqlState("23514", () -> schedule(empty, 1, "08:00", "24:00"));
    }

    @Test
    void packageVersionsHaveAdjacentSaleWindowsAndOneStableScope() throws SQLException {
        long first = draftPackage("MONTHLY", 1, "ALL_DAY", START, START.plusSeconds(86400));
        publish(first);
        long overlapping = draftPackage("MONTHLY", 2, "ALL_DAY", START.plusSeconds(3600), null);
        assertSqlState("23P01", () -> publish(overlapping));
        execute(database, "UPDATE subscription_package SET effective_from = ? WHERE id = ?",
                START.plusSeconds(86400), overlapping);
        publish(overlapping);

        assertSqlState("P0001", () -> execute(database, """
                INSERT INTO subscription_package
                    (package_code, name, version_no, parking_lot_id, vehicle_type, price_vnd,
                     duration_days, coverage_mode, effective_from)
                VALUES ('MONTHLY', 'Wrong lot', 3, 2, 'CAR', 500000, 30, 'ALL_DAY', ?)
                """, START.plusSeconds(172800)));
        assertEquals(2, number(database, "SELECT count(*) FROM subscription_package WHERE status = 'PUBLISHED'"));
    }

    @Test
    void submittedQuoteMustMatchPublishedTermsAndCannotBeEdited() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long subscription = subscription(packageId, START, null);
        assertSqlState("P0001", () -> execute(database,
                "UPDATE vehicle_subscription SET price_vnd = 700000 WHERE id = ?", subscription));
        assertSqlState("P0001", () -> execute(database,
                "UPDATE vehicle_subscription SET starts_at = starts_at + INTERVAL '1 day' WHERE id = ?", subscription));
        assertSqlState("P0001", () -> insertSubscription(database, packageId, START,
                START.plusSeconds(29L * 86400), null, 500000));
        assertSqlState("P0001", () -> insertSubscription(database, packageId, START,
                START.plusSeconds(30L * 86400), null, 700000));

        long draft = draftPackage("UNPUBLISHED", 1, "ALL_DAY", START, null);
        assertSqlState("P0001", () -> subscription(draft, START, null));
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription"));
    }

    @Test
    void pendingOrdersCanOverlapButOnlyOneCanGrantThePeriod() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long first = subscription(packageId, START, null);
        long second = subscription(packageId, START, null);
        assertEquals(0, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
        activate(database, first, "first");

        database.setAutoCommit(false);
        try {
            assertSqlState("23P01", () -> activate(database, second, "second"));
        } finally {
            database.rollback();
            database.setAutoCommit(true);
        }
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number(database,
                "SELECT count(*) FROM vehicle_subscription WHERE id = ? AND status = 'PENDING_PAYMENT'", second));
    }

    @Test
    void renewalIsAdjacentToPaidPredecessorAndTheOldPeriodRemainsImmutable() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long original = subscription(packageId, START, null);
        assertSqlState("P0001", () -> subscription(packageId, START.plusSeconds(30L * 86400), original));
        activate(database, original, "original");
        assertSqlState("P0001", () -> subscription(packageId, START.plusSeconds(31L * 86400), original));
        long renewal = subscription(packageId, START.plusSeconds(30L * 86400), original);
        activate(database, renewal, "renewal");

        assertSqlState("P0001", () -> execute(database,
                "UPDATE vehicle_subscription SET status = 'CANCELLED', paid_at = NULL, paid_receipt_id = NULL WHERE id = ?",
                original));
        assertSqlState("P0001", () -> execute(database, "DELETE FROM vehicle_subscription WHERE id = ?", original));
        Instant boundary = START.plusSeconds(30L * 86400);
        assertEquals(1, number(database, """
                SELECT count(*) FROM vehicle_subscription
                WHERE status = 'PAID' AND starts_at <= ? AND ends_at > ?
                """, boundary, boundary));
        assertEquals(2, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
    }

    @Test
    void receiptAuditRetainsMultipleTransfersAndDeduplicatesPhysicalSettlement() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long subscription = subscription(packageId, START, null);
        long payment = payment(database);
        long receipt = receipt(database, subscription, payment, "tx-1", 400000,
                "REVIEW_REQUIRED", "AMOUNT_MISMATCH", null);
        receipt(database, subscription, payment, "tx-2", 100000,
                "REVIEW_REQUIRED", "AMOUNT_MISMATCH", PAID_AT);
        assertSqlState("23505", () -> receipt(database, subscription, payment, "tx-1", 400000,
                "REVIEW_REQUIRED", "AMOUNT_MISMATCH", null));
        assertSqlState("P0001", () -> execute(database,
                "UPDATE vehicle_subscription_receipt SET amount_vnd = 500000 WHERE id = ?", receipt));
        assertSqlState("P0001", () -> execute(database,
                "DELETE FROM vehicle_subscription_receipt WHERE id = ?", receipt));
        assertEquals(2, number(database, "SELECT count(*) FROM vehicle_subscription_receipt WHERE payment_id = ?", payment));
        assertEquals(0, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
    }

    @Test
    void paidStateRequiresOwnMatchingAppliedReceipt() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long first = subscription(packageId, START, null);
        long second = subscription(packageId, START.plusSeconds(30L * 86400), null);
        long payment = payment(database);
        database.setAutoCommit(false);
        try {
            long wrongAmount = receipt(database, first, payment, "wrong-amount", 400000,
                    "APPLIED", null, PAID_AT);
            assertSqlState("P0001", () -> grant(database, first, wrongAmount));
        } finally {
            database.rollback();
        }
        try {
            long wrongParent = receipt(database, first, payment, "wrong-parent", 500000,
                    "APPLIED", null, PAID_AT);
            assertSqlState("P0001", () -> grant(database, second, wrongParent));
        } finally {
            database.rollback();
            database.setAutoCommit(true);
        }
        long matching = activate(database, first, "matching");
        assertEquals(matching, number(database, "SELECT paid_receipt_id FROM vehicle_subscription WHERE id = ?", first));
    }

    @Test
    void appliedReceiptCannotCommitWithoutGrantingItsOwnParent() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long subscription = subscription(packageId, START, null);
        long payment = payment(database);
        database.setAutoCommit(false);
        try {
            receipt(database, subscription, payment, "orphan", 500000, "APPLIED", null, PAID_AT);
            assertSqlState("P0001", database::commit);
        } finally {
            database.rollback();
            database.setAutoCommit(true);
        }
        assertEquals(0, number(database, "SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PENDING_PAYMENT'"));
        activate(database, subscription, "valid-after-rollback");
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription_receipt"));
    }

    @Test
    void physicalPaymentAfterOrderDeadlineCannotActivateCoverage() throws SQLException {
        long packageId = publishedPackage("MONTHLY");
        long subscription = insertSubscription(database, packageId, START, START.plusSeconds(30L * 86400),
                null, 500000, PAID_AT.minusSeconds(1));
        database.setAutoCommit(false);
        try {
            assertSqlState("23514", () -> activate(database, subscription, "too-late"));
        } finally {
            database.rollback();
            database.setAutoCommit(true);
        }
        assertEquals(0, number(database, "SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PENDING_PAYMENT'"));
    }

    @Test
    void actualJpaMappingsGraphsQueriesLocksAndJsonReceiptRollbackWorkOnV54() throws SQLException {
        long packageId = draftPackage("OFFICE", 1, "WEEKLY", START, null);
        schedule(packageId, 1, "08:00", "17:00");
        publish(packageId);
        long first = subscription(packageId, START, null);
        long receiptId = activate(database, first, "jpa-paid");
        long second = subscription(packageId, START.plusSeconds(30L * 86400), first);
        String requestKey;
        try (PreparedStatement query = prepared(database, "SELECT request_key FROM vehicle_subscription WHERE id = ?", first);
             ResultSet rows = query.executeQuery()) {
            assertTrue(rows.next());
            requestKey = rows.getString(1);
        }
        long paymentId = payment(database);
        LocalContainerEntityManagerFactoryBean factory = isolatedEntityManagerFactory();
        try {
            var emf = factory.getObject();
            assertNotNull(emf);
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
            SubscriptionPackageRepository packages = repositories.getRepository(SubscriptionPackageRepository.class);
            VehicleSubscriptionRepository subscriptions = repositories.getRepository(VehicleSubscriptionRepository.class);
            VehicleSubscriptionReceiptRepository receipts = repositories.getRepository(VehicleSubscriptionReceiptRepository.class);
            TransactionTemplate transaction = new TransactionTemplate(new JpaTransactionManager(emf));

            SubscriptionPackageEntity fetched = transaction.execute(status -> {
                assertEquals(1L, packages.lockParkingLot(1L).orElseThrow());
                assertTrue(packages.parkingLotExists(1L));
                assertEquals(packageId, packages.lockPackage(packageId).orElseThrow().getId());
                assertEquals(1, packages.findPublishedAt(1L, VehicleType.CAR, START).size());
                assertEquals(0, packages.findPublishedAt(1L, VehicleType.MOTORBIKE, START).size());
                assertEquals(1, packages.findByPackageCodeOrderByVersionNoAsc("OFFICE").size());
                assertEquals(1L, subscriptions.lockVehicle(1L).orElseThrow());
                assertEquals(first, subscriptions.lockSubscription(first).orElseThrow());
                assertEquals("CAR", subscriptions.findVehicleType(1L).orElseThrow());
                assertTrue(subscriptions.parkingLotExists(1L));
                assertEquals(first, subscriptions.findByRequestKey(requestKey).orElseThrow().getId());
                assertEquals(2, subscriptions.findByVehicleIdAndParkingLotIdOrderByStartsAtAscIdAsc(1L, 1L).size());
                assertEquals(second, subscriptions.findByPreviousSubscriptionIdOrderByIdAsc(first).getFirst().getId());
                assertEquals(1, subscriptions.findPaidAt(1L, 1L, START).size());
                assertEquals(0, subscriptions.findPaidAt(1L, 1L, START.plusSeconds(30L * 86400)).size());
                assertEquals(1, subscriptions.findPaidOverlapping(1L, 1L, START, START.plusSeconds(86400)).size());
                assertEquals(0, subscriptions.findPaidOverlapping(1L, 1L,
                        START.plusSeconds(30L * 86400), START.plusSeconds(31L * 86400)).size());
                assertEquals(receiptId, receipts.findBySourceAndReference("TEST_VERIFIED", "jpa-paid").orElseThrow().getId());
                assertEquals(1, receipts.findBySubscriptionIdOrderByReceivedAtAscIdAsc(first).size());
                assertEquals(0, receipts.findByPaymentId(paymentId).size());
                assertEquals(1, subscriptions.findById(first).orElseThrow().getSubscriptionPackage().getSchedules().size());
                return packages.findById(packageId).orElseThrow();
            });
            assertNotNull(fetched);
            // Access after transaction close proves the declared entity graph fetched the schedule.
            assertEquals(1, fetched.getSchedules().size());

            transaction.executeWithoutResult(status -> {
                VehicleSubscriptionReceiptEntity review = new VehicleSubscriptionReceiptEntity();
                review.setSubscriptionId(second);
                review.setPaymentId(paymentId);
                review.setSource("TEST_VERIFIED");
                review.setReference("jpa-review");
                review.setInvoiceNo("invoice-" + paymentId);
                review.setCurrency("VND");
                review.setAmountVnd(400000L);
                review.setReceivedAt(PAID_AT);
                review.setOutcome(SubscriptionReceiptOutcome.REVIEW_REQUIRED);
                review.setReasonCode("AMOUNT_MISMATCH");
                review.setMetadata(Map.of("verified", true, "provider", "TEST"));
                receipts.saveAndFlush(review);
                assertNotNull(review.getId());
                entityManager.clear();
                assertEquals(Boolean.TRUE, receipts.findById(review.getId()).orElseThrow().getMetadata().get("verified"));
                status.setRollbackOnly();
            });
            assertEquals(0, number(database,
                    "SELECT count(*) FROM vehicle_subscription_receipt WHERE reference = 'jpa-review'"));
        } finally {
            factory.destroy();
        }
    }

    private LocalContainerEntityManagerFactoryBean isolatedEntityManagerFactory() {
        return SubscriptionJpaTestSupport.entityManagerFactory(schema,
                SubscriptionJpaTestSupport.allTypes(), "validate");
    }

    @Test
    void concurrentOverlappingGrantsWaitAndLoserRollsBackItsReceipt() throws Exception {
        long packageId = publishedPackage("MONTHLY");
        long first = subscription(packageId, START, null);
        long second = subscription(packageId, START, null);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Connection winner = open(); Connection loser = open()) {
            winner.setAutoCommit(false);
            loser.setAutoCommit(false);
            execute(loser, "SET LOCAL statement_timeout = '10s'");
            activate(winner, first, "winner");
            int loserPid = Math.toIntExact(number(loser, "SELECT pg_backend_pid()"));
            CountDownLatch started = new CountDownLatch(1);
            Future<String> losingResult = workers.submit(() -> {
                started.countDown();
                try {
                    activate(loser, second, "loser");
                    loser.commit();
                    return "committed";
                } catch (SQLException failure) {
                    loser.rollback();
                    return failure.getSQLState();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                awaitDatabaseLock(loserPid);
            } finally {
                winner.commit();
            }
            assertEquals("23P01", losingResult.get(10, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
        assertEquals(1, number(database, "SELECT count(*) FROM vehicle_subscription_receipt"));
        assertEquals(1, number(database, "SELECT count(*) FROM payment"));
    }

    private void awaitDatabaseLock(int pid) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            if (number(database, "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'", pid) == 1) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Concurrent grant did not reach the expected PostgreSQL constraint lock");
    }

    private long publishedPackage(String code) throws SQLException {
        long id = draftPackage(code, 1, "ALL_DAY", START, null);
        publish(id);
        return id;
    }

    private long draftPackage(String code, int version, String mode, Instant from, Instant until) throws SQLException {
        return number(database, """
                INSERT INTO subscription_package
                    (package_code, name, version_no, parking_lot_id, vehicle_type, price_vnd,
                     duration_days, coverage_mode, effective_from, effective_to)
                VALUES (?, 'Monthly package', ?, 1, 'CAR', 500000, 30, ?, ?, ?) RETURNING id
                """, code, version, mode, from, until);
    }

    private void publish(long packageId) throws SQLException {
        execute(database, "UPDATE subscription_package SET status = 'PUBLISHED' WHERE id = ?", packageId);
    }

    private void schedule(long packageId, int weekday, String start, String end) throws SQLException {
        execute(database, """
                INSERT INTO subscription_package_schedule (package_id, weekday, start_time, end_time)
                VALUES (?, ?, CAST(? AS TIME), CAST(? AS TIME))
                """, packageId, weekday, start, end);
    }

    private long subscription(long packageId, Instant start, Long previous) throws SQLException {
        return insertSubscription(database, packageId, start, start.plusSeconds(30L * 86400), previous, 500000);
    }

    private long insertSubscription(Connection connection, long packageId, Instant start, Instant end,
                                    Long previous, long price) throws SQLException {
        return insertSubscription(connection, packageId, start, end, previous, price,
                START.plusSeconds(90L * 86400));
    }

    private long insertSubscription(Connection connection, long packageId, Instant start, Instant end,
                                    Long previous, long price, Instant deadline) throws SQLException {
        return number(connection, """
                INSERT INTO vehicle_subscription
                    (request_key, vehicle_id, parking_lot_id, package_id, previous_subscription_id,
                     registration_type, price_vnd, starts_at, ends_at, payment_deadline_at)
                VALUES (?, 1, 1, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.randomUUID().toString(), packageId, previous, previous == null ? "NEW" : "RENEW",
                price, start, end, deadline);
    }

    private long payment(Connection connection) throws SQLException {
        return number(connection, "INSERT INTO payment DEFAULT VALUES RETURNING id");
    }

    private long receipt(Connection connection, long subscription, long payment, String reference,
                         long amount, String outcome, String reason, Instant paidAt) throws SQLException {
        return number(connection, """
                INSERT INTO vehicle_subscription_receipt
                    (subscription_id, payment_id, source, reference, invoice_no, currency, amount_vnd,
                     paid_at, received_at, outcome, reason_code)
                VALUES (?, ?, 'TEST_VERIFIED', ?, ?, 'VND', ?, ?, ?, ?, ?) RETURNING id
                """, subscription, payment, reference, "invoice-" + payment, amount, paidAt, PAID_AT, outcome, reason);
    }

    private long activate(Connection connection, long subscription, String reference) throws SQLException {
        boolean ownsTransaction = connection.getAutoCommit();
        if (ownsTransaction) connection.setAutoCommit(false);
        try {
            long receipt = receipt(connection, subscription, payment(connection), reference,
                    500000, "APPLIED", null, PAID_AT);
            grant(connection, subscription, receipt);
            if (ownsTransaction) connection.commit();
            return receipt;
        } catch (SQLException failure) {
            if (ownsTransaction) connection.rollback();
            throw failure;
        } finally {
            if (ownsTransaction) connection.setAutoCommit(true);
        }
    }

    private void grant(Connection connection, long subscription, long receipt) throws SQLException {
        execute(connection, "UPDATE vehicle_subscription SET status = 'PAID', paid_at = ?, paid_receipt_id = ? WHERE id = ?",
                PAID_AT, receipt, subscription);
    }

    private Connection openRaw() throws SQLException {
        return DriverManager.getConnection(System.getenv("SUBSCRIPTION_TEST_JDBC_URL"), user(), password());
    }

    private Connection open() throws SQLException {
        Connection connection = openRaw();
        execute(connection, "SET search_path TO " + schema + ", public");
        return connection;
    }

    private String user() {
        return System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_USER", "subscription_test");
    }

    private String password() {
        return System.getenv().getOrDefault("SUBSCRIPTION_TEST_DB_PASSWORD", "subscription_test");
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = prepared(connection, sql, arguments)) {
            statement.executeUpdate();
        }
    }

    private static long number(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = prepared(connection, sql, arguments); ResultSet result = statement.executeQuery()) {
            assertTrue(result.next(), "Expected a result from the test query");
            return result.getLong(1);
        }
    }

    private static PreparedStatement prepared(Connection connection, String sql, Object... arguments) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            statement.setObject(index + 1, argument instanceof Instant instant ? Timestamp.from(instant) : argument);
        }
        return statement;
    }

    private static void assertSqlState(String expected, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals(expected, failure.getSQLState());
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
