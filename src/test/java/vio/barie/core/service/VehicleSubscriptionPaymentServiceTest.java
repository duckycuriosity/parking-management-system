package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.service.payment.common.PaymentLinkRejectedException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Contract tests; PostgreSQL tests separately verify real commits, locks and competing callers. */
class VehicleSubscriptionPaymentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T05:00:00Z");
    private VehicleSubscriptionService subscriptions;
    private PaymentService payments;
    private PaymentRepository repository;
    private VehicleSubscriptionPaymentService service;
    private TrackingTransactions transactions;
    private AtomicReference<PaymentEntity> persisted;

    @BeforeEach
    void setUp() {
        subscriptions = mock(VehicleSubscriptionService.class);
        payments = mock(PaymentService.class);
        repository = mock(PaymentRepository.class);
        transactions = new TrackingTransactions();
        persisted = new AtomicReference<>();
        SePayConfig config = new SePayConfig();
        config.setQrExpireMinutes(15);
        config.setInvoicePrefix("SUB");
        service = new VehicleSubscriptionPaymentService(subscriptions, payments, repository,
                Clock.fixed(NOW, ZoneOffset.UTC), new MegaPayConfig(), config, "sepay", transactions);
        when(subscriptions.preparePaymentLink(7L)).thenReturn(SubscriptionResponse.builder().id(7L)
                .priceVnd(50_000L).status(VehicleSubscriptionStatus.PENDING_PAYMENT).paymentDeadlineAt(NOW.plusSeconds(600)).build());
        when(subscriptions.lockForPaymentLinkResult(7L)).thenAnswer(call -> subscriptions.preparePaymentLink(7L));
        when(repository.findLatestPendingBySubscriptionId("7")).thenAnswer(call ->
                Optional.ofNullable(persisted.get()).filter(p -> "PENDING".equals(p.getStatus())));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            PaymentEntity entity = call.getArgument(0);
            if (entity.getId() == null) entity.setId(10L);
            persisted.set(entity);
            return entity;
        });
        when(repository.lockById(10L)).thenAnswer(call -> Optional.ofNullable(persisted.get()));
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Gateway must run outside DB transaction");
            assertTrue(transactions.commits >= 1, "Payment routing must commit before gateway IO");
            assertEquals(call.getArgument(1), persisted.get().getInvoiceNo());
            assertEquals("SUBSCRIPTION", persisted.get().getCallbackType());
            return link(call.getArgument(1), 50_000);
        }).when(payments).createPaymentLink(any(), anyString());
    }

    @Test
    void commitsInvoiceAndFrozenAmountBeforeGatewayAndStoresReadyQrAfterward() {
        CreatePaymentResponse result = service.createPaymentLink(7L);
        assertEquals(persisted.get().getInvoiceNo(), result.getInvoiceNo());
        assertTrue(result.getInvoiceNo().matches("SUB[0-9]+"));
        assertEquals(BigDecimal.valueOf(50_000), persisted.get().getAmount());
        assertEquals(7L, persisted.get().getCallbackContext().getLong("subscriptionId"));
        assertEquals(NOW.plusSeconds(600).toString(), persisted.get().getCallbackContext().getStr("linkExpiresAt"));
        assertEquals("PENDING", persisted.get().getStatus());
        assertEquals("READY", persisted.get().getCallbackContext().getStr("linkState"));
        assertEquals(2, transactions.commits);
        var order = inOrder(repository, payments);
        order.verify(repository).findLatestPendingBySubscriptionId("7");
        order.verify(repository).saveAndFlush(any());
        order.verify(payments).createPaymentLink(any(), eq(result.getInvoiceNo()));
        order.verify(repository).lockById(10L);
    }

    @Test
    void repeatedClickReusesUnexpiredIntent() {
        persisted.set(intent("SUB888", NOW.plusSeconds(120), 50_000));
        assertEquals("SUB888", service.createPaymentLink(7L).getInvoiceNo());
        verifyNoInteractions(payments);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void expiredQrCanBeReplacedWhileOldPaymentRetainsLateTransferRouting() {
        PaymentEntity previous = intent("SUB888", NOW, 50_000);
        persisted.set(previous);
        String replacement = service.createPaymentLink(7L).getInvoiceNo();
        assertNotEquals(previous.getInvoiceNo(), replacement);
        assertEquals("PENDING", previous.getStatus());
        assertEquals(7L, previous.getCallbackContext().getLong("subscriptionId"));
    }

    @Test
    void competingClickDuringGatewayRequestDoesNotCreateSecondInvoice() {
        doAnswer(call -> {
            String invoice = call.getArgument(1);
            BusinessException second = assertThrows(BusinessException.class, () -> service.createPaymentLink(7L));
            assertEquals("SUBSCRIPTION_PAYMENT_LINK_IN_PROGRESS", second.getCode());
            assertEquals(invoice, persisted.get().getInvoiceNo());
            return link(invoice, 50_000);
        }).when(payments).createPaymentLink(any(), anyString());
        service.createPaymentLink(7L);
        verify(payments, times(1)).createPaymentLink(any(), anyString());
    }

    @Test
    void timeoutRetainsUnknownIntentAndRetryDoesNotCreateAnotherInvoice() {
        doThrow(new RuntimeException("Lost gateway response")).when(payments).createPaymentLink(any(), anyString());
        BusinessException first = assertThrows(BusinessException.class, () -> service.createPaymentLink(7L));
        assertEquals("SUBSCRIPTION_PAYMENT_LINK_UNKNOWN", first.getCode());
        String invoice = persisted.get().getInvoiceNo();
        assertEquals("PENDING", persisted.get().getStatus());
        assertEquals("UNKNOWN", persisted.get().getCallbackContext().getStr("linkState"));
        assertEquals(first.getCode(), assertThrows(BusinessException.class, () -> service.createPaymentLink(7L)).getCode());
        assertEquals(invoice, persisted.get().getInvoiceNo());
        verify(payments, times(1)).createPaymentLink(any(), anyString());
    }

    @Test
    void definitiveRejectionIsDistinctAndAllowsNewAttemptWithoutRecreatingOrder() {
        doThrow(new PaymentLinkRejectedException("Gateway declined", 400))
                .when(payments).createPaymentLink(any(), anyString());
        assertEquals("SUBSCRIPTION_PAYMENT_LINK_REJECTED",
                assertThrows(BusinessException.class, () -> service.createPaymentLink(7L)).getCode());
        PaymentEntity rejected = persisted.get();
        assertEquals("FAILED", rejected.getStatus());
        assertEquals("REJECTED", rejected.getCallbackContext().getStr("linkState"));
        doAnswer(call -> link(call.getArgument(1), 50_000)).when(payments).createPaymentLink(any(), anyString());
        assertNotEquals(rejected.getInvoiceNo(), service.createPaymentLink(7L).getInvoiceNo());
        assertEquals("FAILED", rejected.getStatus());
    }

    @Test
    void gatewayCannotChangeInvoiceOrFrozenAmount() {
        doAnswer(call -> link(call.getArgument(1), 49_000)).when(payments).createPaymentLink(any(), anyString());
        assertEquals("SUBSCRIPTION_PAYMENT_LINK_UNKNOWN",
                assertThrows(BusinessException.class, () -> service.createPaymentLink(7L)).getCode());
        assertEquals(BigDecimal.valueOf(50_000), persisted.get().getAmount());
        assertEquals("UNKNOWN", persisted.get().getCallbackContext().getStr("linkState"));
    }

    @Test
    void earlyCallbackPaidStateIsNotOverwrittenByLinkResponse() {
        earlyCallbackKeepsOutcome("PAID");
    }

    @Test
    void earlyCallbackReviewStateIsNotOverwrittenByLinkResponse() {
        earlyCallbackKeepsOutcome("REVIEW_REQUIRED");
    }

    private void earlyCallbackKeepsOutcome(String status) {
        doAnswer(call -> {
            assertEquals(1, transactions.commits);
            persisted.get().setStatus(status);
            persisted.get().setResultCode("RECEIPT_OUTCOME");
            return link(call.getArgument(1), 50_000);
        }).when(payments).createPaymentLink(any(), anyString());
        assertEquals("SUBSCRIPTION_PAYMENT_ALREADY_PROCESSED",
                assertThrows(BusinessException.class, () -> service.createPaymentLink(7L)).getCode());
        assertEquals(status, persisted.get().getStatus());
        assertEquals("RECEIPT_OUTCOME", persisted.get().getResultCode());
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void corruptOrMismatchedExistingIntentCannotBeSilentlyReplaced() {
        persisted.set(intent("SUB888", NOW.plusSeconds(60), 1));
        assertEquals("SUBSCRIPTION_PAYMENT_INTENT_CONFLICT",
                assertThrows(BusinessException.class, () -> service.createPaymentLink(7L)).getCode());
        verifyNoInteractions(payments);
    }

    @Test
    void duplicateInvoiceAbortsBeforeGatewayWithoutQueryingFailedPersistenceContext() {
        DataIntegrityViolationException duplicate = new DataIntegrityViolationException("Duplicate invoice",
                new org.hibernate.exception.ConstraintViolationException("Duplicate invoice",
                        new SQLException("Duplicate invoice", "23505"), "uq_payment_invoice_no"));
        doThrow(duplicate).when(repository).saveAndFlush(any());
        assertEquals("SUBSCRIPTION_INVOICE_CONFLICT",
                assertThrows(BusinessException.class, () -> service.createPaymentLink(7L)).getCode());
        verifyNoInteractions(payments);
        verify(repository, never()).findByInvoiceNo(anyString());
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void coreRejectionPreventsAnyPaymentWork() {
        when(subscriptions.preparePaymentLink(7L)).thenThrow(new BusinessException("Not pending", "SUBSCRIPTION_NOT_PENDING", 409));
        assertThrows(BusinessException.class, () -> service.createPaymentLink(7L));
        verifyNoInteractions(payments, repository);
    }

    private CreatePaymentResponse link(String invoice, long amount) {
        CreatePaymentResponse response = new CreatePaymentResponse();
        response.setResultCd("00_000");
        response.setInvoiceNo(invoice);
        response.setAmount(BigDecimal.valueOf(amount));
        response.setPaymentLink("https://example.test/qr/" + invoice);
        response.setPayType("QR");
        response.setLinkExptime("20261009123000");
        return response;
    }

    private PaymentEntity intent(String invoice, Instant expiresAt, long amount) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(10L);
        entity.setInvoiceNo(invoice);
        entity.setAmount(BigDecimal.valueOf(amount));
        entity.setCurrency("VND");
        entity.setStatus("PENDING");
        entity.setCallbackType("SUBSCRIPTION");
        entity.setGateway("SEPAY");
        JSONObject context = new JSONObject();
        context.set("subscriptionId", 7L);
        context.set("linkExpiresAt", expiresAt.toString());
        context.set("paymentLink", "https://example.test/qr/" + invoice);
        context.set("linkState", "READY");
        entity.setCallbackContext(context);
        return entity;
    }

    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
