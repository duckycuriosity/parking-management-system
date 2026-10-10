package vio.barie.core.persistence;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.subscription.RegisterSubscriptionRequest;
import vio.barie.core.dto.subscription.VerifiedSubscriptionPayment;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.repository.VehicleSubscriptionReceiptRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.service.PaymentService;
import vio.barie.core.service.VehicleSubscriptionPaymentService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL proof of commit-before-IO, concurrent claims, and early receipt settlement. */
@EnabledIfEnvironmentVariable(named = "SUBSCRIPTION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class VehicleSubscriptionPaymentLinkPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");
    private String schema;
    private LocalContainerEntityManagerFactoryBean factory;
    private VehicleSubscriptionService subscriptions;
    private VehicleSubscriptionPaymentService links;
    private PaymentService gateway;
    private long subscriptionId;

    @BeforeEach
    void createIsolatedDatabaseAndServices() throws SQLException {
        schema = "subscription_qr_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = SubscriptionJpaTestSupport.openRaw()) {
            SubscriptionJpaTestSupport.execute(connection, "CREATE SCHEMA " + schema);
        }
        SubscriptionJpaTestSupport.createLegacySchemaAndSeed(schema);
        SubscriptionJpaTestSupport.migrate(schema, "55");
        long packageId = scalar("""
                INSERT INTO subscription_package
                  (package_code, name, version_no, parking_lot_id, vehicle_type, price_vnd,
                   duration_days, coverage_mode, effective_from, status)
                VALUES ('QR', 'QR test', 1, 1, 'CAR', 500000, 30, 'ALL_DAY', ?, 'PUBLISHED') RETURNING id
                """, Timestamp.from(NOW.minusSeconds(60)));
        factory = SubscriptionJpaTestSupport.entityManagerFactory(schema, SubscriptionJpaTestSupport.allTypes(), "validate");
        var emf = factory.getObject();
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        var repositories = new JpaRepositoryFactory(entityManager);
        repositories.addRepositoryProxyPostProcessor((proxy, metadata) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        var manager = new JpaTransactionManager(emf);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        subscriptions = advised(new VehicleSubscriptionService(
                repositories.getRepository(VehicleSubscriptionRepository.class),
                repositories.getRepository(SubscriptionPackageRepository.class),
                repositories.getRepository(VehicleSubscriptionReceiptRepository.class),
                repositories.getRepository(PaymentRepository.class), clock, entityManager, event -> { }), manager);
        gateway = mock(PaymentService.class);
        SePayConfig config = new SePayConfig();
        config.setQrExpireMinutes(15);
        config.setInvoicePrefix("SUB");
        links = advised(new VehicleSubscriptionPaymentService(subscriptions, gateway,
                repositories.getRepository(PaymentRepository.class), clock,
                new MegaPayConfig(), config, "sepay", manager), manager);
        RegisterSubscriptionRequest request = new RegisterSubscriptionRequest();
        request.setVehicleId(1L);
        request.setParkingLotId(1L);
        request.setPackageId(packageId);
        request.setStartsAt(NOW.minusSeconds(60));
        request.setPaymentDeadlineAt(NOW.plusSeconds(3600));
        request.setRequestKey(UUID.randomUUID().toString());
        subscriptionId = subscriptions.register(request).getId();
    }

    @AfterEach
    void discardOnlyTestSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (schema != null) {
            try (Connection connection = SubscriptionJpaTestSupport.openRaw()) {
                SubscriptionJpaTestSupport.execute(connection, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void committedRoutingIsVisibleDuringGatewayIoAndSecondCallerDoesNotWaitOnHeldRowLocks() throws Exception {
        CountDownLatch gatewayEntered = new CountDownLatch(1);
        CountDownLatch gatewayRelease = new CountDownLatch(1);
        when(gateway.createPaymentLink(any(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, scalar("SELECT count(*) FROM payment WHERE invoice_no = ? AND callback_type = 'SUBSCRIPTION' "
                    + "AND callback_context->>'subscriptionId' = ? AND callback_context->>'linkState' = 'CREATING'",
                    call.getArgument(1), Long.toString(subscriptionId)));
            gatewayEntered.countDown();
            assertTrue(gatewayRelease.await(15, TimeUnit.SECONDS));
            return response(call.getArgument(1));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> links.createPaymentLink(subscriptionId));
            assertTrue(gatewayEntered.await(15, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(BusinessException.class,
                    () -> links.createPaymentLink(subscriptionId)));
            assertEquals("SUBSCRIPTION_PAYMENT_LINK_IN_PROGRESS", second.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(1, scalar("SELECT count(*) FROM payment"));
            gatewayRelease.countDown();
            String invoice = first.get(15, TimeUnit.SECONDS).getInvoiceNo();
            assertEquals(invoice, links.createPaymentLink(subscriptionId).getInvoiceNo());
            assertEquals(1, scalar("SELECT count(*) FROM payment WHERE callback_context->>'linkState' = 'READY'"));
            verify(gateway, times(1)).createPaymentLink(any(), anyString());
        } finally {
            gatewayRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void earlyVerifiedCallbackCommitsReceiptAndPaidStateCannotBeOverwrittenByQrResponse() throws Exception {
        earlyCallback(null, "PAID");
        assertEquals(1, scalar("SELECT count(*) FROM vehicle_subscription_receipt WHERE outcome = 'APPLIED'"));
        assertEquals(1, scalar("SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID' AND paid_receipt_id IS NOT NULL"));
    }

    @Test
    void earlyCallbackWithoutTrustedPaidAtKeepsReviewRequired() throws Exception {
        earlyCallback("missing", "REVIEW_REQUIRED");
        assertEquals(1, scalar("SELECT count(*) FROM vehicle_subscription_receipt WHERE outcome = 'REVIEW_REQUIRED'"));
        assertEquals(0, scalar("SELECT count(*) FROM vehicle_subscription WHERE status = 'PAID'"));
    }

    private void earlyCallback(String paidAtMissing, String expectedStatus) throws Exception {
        when(gateway.createPaymentLink(any(), anyString())).thenAnswer(call -> {
            String invoice = call.getArgument(1);
            long paymentId = scalar("SELECT id FROM payment WHERE invoice_no = ?", invoice);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            subscriptions.confirmVerifiedPayment(new VerifiedSubscriptionPayment(subscriptionId, paymentId,
                    "SEPAY", "early_callback", invoice, 500000L, "VND",
                    paidAtMissing == null ? NOW : null, NOW, Map.of("verified_by", "fixture_gateway")));
            return response(invoice);
        });
        BusinessException processed = assertThrows(BusinessException.class, () -> links.createPaymentLink(subscriptionId));
        assertEquals("SUBSCRIPTION_PAYMENT_ALREADY_PROCESSED", processed.getCode());
        assertEquals(1, scalar("SELECT count(*) FROM payment WHERE status = ?", expectedStatus));
        assertEquals(0, scalar("SELECT count(*) FROM payment WHERE callback_context->>'linkState' = 'READY'"));
    }

    @Test
    void lateTransferToExpiredOldInvoiceCannotExposeReplacementQrAfterOrderPaid() throws Exception {
        String oldInvoice = "SUB999999";
        String context = "{\"subscriptionId\":" + subscriptionId + ",\"linkState\":\"READY\","
                + "\"linkExpiresAt\":\"" + NOW.minusSeconds(60) + "\",\"paymentLink\":\"https://example.test/old\"}";
        long oldPaymentId = scalar("""
                INSERT INTO payment (invoice_no, amount, currency, status, payment_method, pay_type,
                    callback_type, callback_context, gateway, created_at)
                VALUES (?, 500000, 'VND', 'PENDING', 'QR', 'QR', 'SUBSCRIPTION', CAST(? AS jsonb), 'SEPAY', ?)
                RETURNING id
                """, oldInvoice, context, Timestamp.from(NOW.minusSeconds(3600)));
        when(gateway.createPaymentLink(any(), anyString())).thenAnswer(call -> {
            assertNotEquals(oldInvoice, call.getArgument(1));
            subscriptions.confirmVerifiedPayment(new VerifiedSubscriptionPayment(subscriptionId, oldPaymentId,
                    "SEPAY", "old_invoice_transfer", oldInvoice, 500000L, "VND", NOW, NOW, Map.of()));
            return response(call.getArgument(1));
        });
        assertEquals("SUBSCRIPTION_PAYMENT_ALREADY_PROCESSED",
                assertThrows(BusinessException.class, () -> links.createPaymentLink(subscriptionId)).getCode());
        assertEquals(1, scalar("SELECT count(*) FROM payment WHERE invoice_no = ? AND status = 'PAID'", oldInvoice));
        assertEquals(1, scalar("SELECT count(*) FROM payment WHERE status = 'PENDING' AND callback_context->>'linkState' = 'OBSOLETE'"));
        assertEquals(1, scalar("SELECT count(*) FROM vehicle_subscription_receipt WHERE outcome = 'APPLIED'"));
    }

    @Test
    void gatewayTimeoutKeepsOneDurableUnknownInvoiceOnRepeatedRequests() throws Exception {
        when(gateway.createPaymentLink(any(), anyString())).thenThrow(new RuntimeException("Socket timeout"));
        assertEquals("SUBSCRIPTION_PAYMENT_LINK_UNKNOWN",
                assertThrows(BusinessException.class, () -> links.createPaymentLink(subscriptionId)).getCode());
        assertEquals("SUBSCRIPTION_PAYMENT_LINK_UNKNOWN",
                assertThrows(BusinessException.class, () -> links.createPaymentLink(subscriptionId)).getCode());
        assertEquals(1, scalar("SELECT count(*) FROM payment WHERE status = 'PENDING' AND callback_context->>'linkState' = 'UNKNOWN'"));
        assertEquals(0, scalar("SELECT count(*) FROM vehicle_subscription_receipt"));
        verify(gateway, times(1)).createPaymentLink(any(), anyString());
    }

    private static CreatePaymentResponse response(String invoice) {
        CreatePaymentResponse response = new CreatePaymentResponse();
        response.setInvoiceNo(invoice);
        response.setAmount(BigDecimal.valueOf(500000));
        response.setResultCd("00_000");
        response.setPayType("QR");
        response.setPaymentLink("https://example.test/qr/" + invoice);
        return response;
    }

    private long scalar(String sql, Object... parameters) throws SQLException {
        try (Connection connection = SubscriptionJpaTestSupport.open(schema);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T advised(T target, JpaTransactionManager manager) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
