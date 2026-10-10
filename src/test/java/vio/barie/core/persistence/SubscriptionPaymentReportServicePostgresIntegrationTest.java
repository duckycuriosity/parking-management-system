package vio.barie.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.service.SubscriptionPaymentReportService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Aggregation fixtures only; real settlement/rollback writes are tested in the service PG suite. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class SubscriptionPaymentReportServicePostgresIntegrationTest {
    private static final LocalDateTime FROM = LocalDateTime.parse("2026-10-09T00:00:00");
    private static final LocalDateTime TO = LocalDateTime.parse("2026-10-10T00:00:00");
    private String schema;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transaction;
    private SubscriptionPaymentReportService receipts;
    private PaymentRepository payments;

    @BeforeEach
    void createOnlyDisposableReportFixture() throws SQLException {
        schema = "subscription_report_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(connection, "CREATE SCHEMA " + schema);
        }
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "55");
        try (Connection connection = SubscriptionJpaTestSupport.open(schema)) {
            // Deliberately insert independently known financial facts after the business-trigger migration.
            // This fixture does not bypass production service validation or exercise a real payment gateway.
            SubscriptionJpaTestSupport.execute(connection, """
                    INSERT INTO subscription_package
                        (id, package_code, name, version_no, parking_lot_id, vehicle_type,
                         price_vnd, duration_days, coverage_mode, effective_from, status)
                    VALUES (1, 'REPORT1', 'Main lot plan', 1, 1, 'CAR', 300000, 30, 'ALL_DAY',
                                '2026-10-01T00:00:00Z', 'PUBLISHED'),
                           (2, 'REPORT2', 'Other lot plan', 1, 2, 'CAR', 900000, 30, 'ALL_DAY',
                                '2026-10-01T00:00:00Z', 'PUBLISHED')
                    """);
            SubscriptionJpaTestSupport.execute(connection, """
                    INSERT INTO vehicle_subscription
                        (id, request_key, vehicle_id, parking_lot_id, package_id, registration_type,
                         price_vnd, starts_at, ends_at, payment_deadline_at, status)
                    VALUES (1, 'report-order-1', 1, 1, 1, 'NEW', 300000,
                               '2026-10-09T00:00:00Z', '2026-11-08T00:00:00Z',
                               '2026-10-09T23:00:00Z', 'PENDING_PAYMENT'),
                           (2, 'report-order-2', 2, 2, 2, 'NEW', 900000,
                               '2026-10-09T00:00:00Z', '2026-11-08T00:00:00Z',
                               '2026-10-09T23:00:00Z', 'PAYMENT_EXCEPTION')
                    """);
            SubscriptionJpaTestSupport.execute(connection, """
                    INSERT INTO payment
                        (id, invoice_no, amount, currency, status, callback_type, callback_context, paid_at, created_at)
                    VALUES (100, 'REPORT-INVOICE-1', 300000, 'VND', 'PAID', 'SUBSCRIPTION',
                               '{"subscriptionId":1}', '2026-10-09T02:00:00', '2026-10-09T01:00:00'),
                           (101, 'REPORT-INVOICE-2', 900000, 'VND', 'REVIEW_REQUIRED', 'SUBSCRIPTION',
                               '{"subscriptionId":2}', '2026-10-09T03:00:00', '2026-10-09T01:00:00')
                    """);
            SubscriptionJpaTestSupport.execute(connection, """
                    INSERT INTO vehicle_subscription_receipt
                        (id, subscription_id, payment_id, source, reference, invoice_no, currency,
                         amount_vnd, paid_at, received_at, outcome, reason_code)
                    VALUES (1, 1, 100, 'SEPAY', 'REPORT-TX1', 'REPORT-INVOICE-1', 'VND', 300000,
                               '2026-10-09T02:00:00Z', '2026-10-09T02:00:01Z', 'APPLIED', NULL),
                           (2, 1, 100, 'SEPAY', 'REPORT-TX2', 'REPORT-INVOICE-1', 'VND', 300000,
                               '2026-10-09T03:00:00Z', '2026-10-09T03:00:01Z', 'REVIEW_REQUIRED', 'SECOND_SETTLEMENT'),
                           (3, 2, 101, 'SEPAY', 'REPORT-TX3', 'REPORT-INVOICE-2', 'VND', 900000,
                               '2026-10-09T03:00:00Z', '2026-10-09T03:00:01Z', 'REVIEW_REQUIRED', 'AMOUNT_MISMATCH'),
                           (4, 1, 100, 'SEPAY', 'REPORT-USD', 'REPORT-INVOICE-1', 'USD', 100,
                               '2026-10-09T04:00:00Z', '2026-10-09T04:00:01Z', 'REVIEW_REQUIRED', 'CURRENCY_MISMATCH'),
                           (5, 1, 100, 'SEPAY', 'REPORT-NO-TIME', 'REPORT-INVOICE-1', 'VND', 50000,
                               NULL, '2026-10-10T00:00:00Z', 'REVIEW_REQUIRED', 'PAYMENT_TIME_UNKNOWN')
                    """);
            SubscriptionJpaTestSupport.execute(connection, """
                    UPDATE vehicle_subscription
                    SET status = 'PAID', paid_at = '2026-10-09T02:00:00Z', paid_receipt_id = 1 WHERE id = 1
                    """);
        }
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema, SubscriptionJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        assertNotNull(emf);
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        receipts = new SubscriptionPaymentReportService(entityManager);
        payments = new JpaRepositoryFactory(entityManager).getRepository(PaymentRepository.class);
        transaction = new TransactionTemplate(new JpaTransactionManager(emf));
    }

    @AfterEach
    void discardOnlyDisposableSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (schema != null) try (Connection connection = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void actualCashKeepsBothTransfersAndFiltersLotAndBuildingWithoutQuoteFanout() {
        transaction.executeWithoutResult(status -> {
            var cash = receipts.summarize(FROM, TO, 1L, 1L);
            assertAmount("600000", cash.receivedAmount());
            assertAmount("300000", cash.appliedAmount());
            assertAmount("300000", cash.reviewAmount());
            assertEquals(2, cash.receivedCount());
            assertEquals(1, cash.appliedCount());
            assertAmount("600000", receipts.summarize(FROM, TO, null, 1L).receivedAmount());
            assertAmount("900000", receipts.summarize(FROM, TO, 2L, 2L).receivedAmount());
            assertAmount("0", receipts.summarize(FROM, TO, 1L, 2L).receivedAmount());
            assertAmount("1500000", receipts.summarize(FROM, TO, null, null).receivedAmount());

            // Request status counts retain compatibility, but root amount is excluded from money SUM.
            var legacy = payments.getRevenueSummary(FROM, TO, 1L, 1L);
            assertAmount("0", legacy.get("revenue", BigDecimal.class));
            assertEquals(1L, ((Number) legacy.get("successful_count")).longValue());
        });
    }

    @Test
    void dailyBucketsUseUtcAndUnknownPaidAtFallsBackOnlyForReportObservation() throws SQLException {
        transaction.executeWithoutResult(status -> {
            var days = receipts.daily(FROM, TO.plusDays(1), 1L, 1L);
            assertEquals(2, days.size());
            assertEquals(LocalDate.parse("2026-10-10"), days.getFirst().day());
            assertAmount("50000", days.getFirst().totals().receivedAmount());
            assertAmount("0", days.getFirst().totals().appliedAmount());
            assertEquals(LocalDate.parse("2026-10-09"), days.get(1).day());
            assertAmount("600000", days.get(1).totals().receivedAmount());
            // Half-open date filter includes the observation at 00:00 only in the next day.
            assertAmount("50000", receipts.summarize(TO, TO.plusDays(1), 1L, 1L).reviewAmount());
        });
        try (Connection connection = SubscriptionJpaTestSupport.open(schema);
             var statement = connection.prepareStatement("SELECT paid_at FROM vehicle_subscription_receipt WHERE id = 5");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertNull(result.getTimestamp(1));
        }
    }

    @Test
    void requestListIncludesSubscriptionPlateAndScopeOnceDespiteMultipleReceipts() {
        transaction.executeWithoutResult(status -> {
            var page = payments.findPaymentListRaw("ALL", 1L, 1L, FROM, TO, PageRequest.of(0, 1));
            assertEquals(1, page.getTotalElements());
            assertEquals("30A12345", page.getContent().getFirst()[0]);
            assertEquals("Đóng thuê bao", page.getContent().getFirst()[1]);
            assertTrue(payments.findPaymentListRaw("ALL", 2L, 1L, FROM, TO, PageRequest.of(0, 10)).isEmpty());
            assertTrue(payments.findDailyRevenue(FROM, TO, List.of(LocalDate.parse("2026-10-11")), 1L, 1L).isEmpty());
        });
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
