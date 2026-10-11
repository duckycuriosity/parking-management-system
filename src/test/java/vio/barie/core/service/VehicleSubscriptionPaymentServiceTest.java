package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.service.payment.common.PaymentGateway;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VehicleSubscriptionPaymentServiceTest {
    private final EntityManager em = mock(EntityManager.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final VehicleSubscriptionEntity subscription = new VehicleSubscriptionEntity();
    private final VehicleEntity vehicle = new VehicleEntity();
    private final List<PaymentEntity> payments = new ArrayList<>();
    private VehicleSubscriptionPaymentService service;
    private PaymentEntity payment;

    @BeforeEach
    void setup() {
        service = new VehicleSubscriptionPaymentService(em, new LocalTransactionManager(), gateway);
        subscription.setId(41L);
        subscription.setVehicleId(31L);
        subscription.setStatus("PENDING_PAYMENT");
        subscription.setPriceVnd(10000L);
        subscription.setPaymentDeadlineAt(OffsetDateTime.now().plusDays(1));
        subscription.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        subscription.setStartsAt(OffsetDateTime.now());
        subscription.setEndsAt(OffsetDateTime.now().plusDays(30));
        subscription.setParkingLotId(21L);
        vehicle.setOwnerId(11L);
        when(em.find(VehicleSubscriptionEntity.class, 41L)).thenReturn(subscription);
        when(em.find(VehicleEntity.class, 31L)).thenReturn(vehicle);
        when(em.find(eq(VehicleEntity.class), eq(31L), any(LockModeType.class))).thenReturn(vehicle);
        when(em.find(eq(PaymentEntity.class), anyLong(), any(LockModeType.class))).thenAnswer(call -> payments.getFirst());
        Query previous = mock(Query.class);
        when(em.createNativeQuery(anyString(), eq(PaymentEntity.class))).thenReturn(previous);
        when(previous.setParameter(anyString(), any())).thenReturn(previous);
        when(previous.setMaxResults(anyInt())).thenReturn(previous);
        when(previous.getResultList()).thenAnswer(call -> payments);
        doAnswer(call -> {
            payment = call.getArgument(0);
            payment.setId(51L);
            payments.add(payment);
            return null;
        }).when(em).persist(any(PaymentEntity.class));
        when(gateway.generateInvoiceNo(51L)).thenReturn("viotech_123456789");
        when(gateway.gatewayName()).thenReturn("SEPAY");
        when(gateway.createsLinkRemotely()).thenReturn(false);
        when(gateway.createPaymentLink(any(CreatePaymentRequest.class))).thenAnswer(call -> response(call.getArgument(0)));
    }

    @Test
    void invoiceIsPersistedBeforeGatewayAndGatewayRunsOutsideTransaction() {
        when(gateway.createPaymentLink(any())).thenAnswer(call -> {
            assertEquals("CREATING", payment.getCallbackContext().getStr("qrState"));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            verify(em).flush();
            return response(call.getArgument(0));
        });
        CreatePaymentResponse result = service.createPaymentLink(11L, 41L);
        assertEquals("viotech_123456789", result.getInvoiceNo());
        assertEquals("READY", payment.getCallbackContext().getStr("qrState"));
        assertEquals(41L, payment.getCallbackContext().getLong("subscriptionId"));
    }

    @Test
    void repeatedRequestReturnsSavedQrWithoutNewInvoiceOrGatewayCall() {
        CreatePaymentResponse first = service.createPaymentLink(11L, 41L);
        CreatePaymentResponse second = service.createPaymentLink(11L, 41L);
        assertEquals(first.getPaymentLink(), second.getPaymentLink());
        verify(em, times(1)).persist(any(PaymentEntity.class));
        verify(gateway, times(1)).createPaymentLink(any());
    }

    @Test
    void unknownRemoteResultDoesNotSendAnotherCreateOrAllocateInvoice() {
        when(gateway.createsLinkRemotely()).thenReturn(true);
        when(gateway.createPaymentLink(any())).thenThrow(new RuntimeException("timeout"));
        assertThrows(RuntimeException.class, () -> service.createPaymentLink(11L, 41L));
        assertEquals("UNKNOWN", payment.getCallbackContext().getStr("qrState"));
        ServiceException retry = assertThrows(ServiceException.class, () -> service.createPaymentLink(11L, 41L));
        assertEquals(409, retry.getStatusCode());
        verify(em, times(1)).persist(any(PaymentEntity.class));
        verify(gateway, times(1)).createPaymentLink(any());
    }

    @Test
    void localQrFailureCanRetryExistingInvoice() {
        when(gateway.createPaymentLink(any())).thenThrow(new RuntimeException("configuration"))
                .thenAnswer(call -> response(call.getArgument(0)));
        assertThrows(RuntimeException.class, () -> service.createPaymentLink(11L, 41L));
        assertEquals("REJECTED", payment.getCallbackContext().getStr("qrState"));
        service.createPaymentLink(11L, 41L);
        verify(em, times(1)).persist(any(PaymentEntity.class));
        assertEquals("READY", payment.getCallbackContext().getStr("qrState"));
    }

    @Test
    void gatewayResponseDoesNotOverwritePaymentSettledByEarlyCallback() {
        when(gateway.createPaymentLink(any())).thenAnswer(call -> {
            payment.setStatus("PAID");
            subscription.setStatus("PAID");
            return response(call.getArgument(0));
        });
        service.createPaymentLink(11L, 41L);
        assertEquals("PAID", payment.getStatus());
        assertEquals("PAID", subscription.getStatus());
    }

    @Test
    void differentOwnerCannotCreateQr() {
        ServiceException exception = assertThrows(ServiceException.class, () -> service.createPaymentLink(12L, 41L));
        assertEquals(404, exception.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test
    void verifiedPaymentIsAppliedWithInlineEvidenceAndExpectedAmount() {
        service.createPaymentLink(11L, 41L);
        mockCallbackQueries(List.of(), 0L);
        OffsetDateTime paidAt = OffsetDateTime.now();
        service.applyVerifiedPayment(payment.getInvoiceNo(), "SEPAY", "transfer-1", new BigDecimal("10000"),
                "VND", paidAt, "OK", "verified");
        assertEquals("PAID", subscription.getStatus());
        assertEquals("PAID", payment.getStatus());
        assertEquals(paidAt, subscription.getPaidAt());
        assertEquals("transfer-1", payment.getSepayTxId());
        JSONObject evidence = JSONUtil.parseObj(subscription.getPaymentReceipt()).getJSONArray("transactions").getJSONObject(0);
        assertEquals("APPLIED", evidence.getStr("applicationStatus"));
        assertEquals("10000", evidence.getBigDecimal("amount").toPlainString());
    }

    @Test
    void missingGatewayTimeStoresReviewWithoutGrantingPeriod() {
        service.createPaymentLink(11L, 41L);
        mockCallbackQueries(List.of(), 0L);
        service.applyVerifiedPayment(payment.getInvoiceNo(), "SEPAY", "transfer-2", new BigDecimal("10000"),
                "VND", null, "OK", "verified");
        assertEquals("PENDING_PAYMENT", subscription.getStatus());
        assertEquals("PENDING", payment.getStatus());
        assertEquals("MISSING_TRUSTED_PAID_AT", payment.getResultMessage());
        assertNull(subscription.getPaidAt());
        assertEquals("REVIEW", JSONUtil.parseObj(subscription.getPaymentReceipt()).getJSONArray("transactions")
                .getJSONObject(0).getStr("applicationStatus"));
    }

    @Test
    void duplicateTransferDoesNotAppendOrActivateAgain() {
        service.createPaymentLink(11L, 41L);
        OffsetDateTime paidAt = OffsetDateTime.now();
        mockCallbackQueries(List.of(new JSONObject().set("invoiceNo", payment.getInvoiceNo()).set("amount", 10000)
                .set("currency", "VND").set("paidAt", paidAt.toString()).toString()), 0L);
        service.applyVerifiedPayment(payment.getInvoiceNo(), "SEPAY", "transfer-1", new BigDecimal("10000"),
                "VND", paidAt, "OK", "verified");
        assertNull(subscription.getPaymentReceipt());
        assertEquals("PENDING_PAYMENT", subscription.getStatus());
    }

    @Test
    void invalidAmountCurrencyDeadlineOrSecondPaymentCannotActivate() {
        service.createPaymentLink(11L, 41L);
        OffsetDateTime deadline = OffsetDateTime.now().minusMinutes(1);
        subscription.setPaymentDeadlineAt(deadline);
        assertNull(VehicleSubscriptionPaymentService.validationFailure(subscription, payment, "SEPAY",
                new BigDecimal("10000"), "VND", deadline));
        assertEquals("PAYMENT_AFTER_DEADLINE", VehicleSubscriptionPaymentService.validationFailure(subscription, payment,
                "SEPAY", new BigDecimal("10000"), "VND", deadline.plusNanos(1)));
        assertEquals("AMOUNT_MISMATCH", VehicleSubscriptionPaymentService.validationFailure(subscription, payment,
                "SEPAY", new BigDecimal("9999"), "VND", deadline));
        assertEquals("CURRENCY_MISMATCH", VehicleSubscriptionPaymentService.validationFailure(subscription, payment,
                "SEPAY", new BigDecimal("10000"), "USD", deadline));
        subscription.setStatus("PAID");
        assertEquals("ORDER_NOT_PENDING_PAYMENT", VehicleSubscriptionPaymentService.validationFailure(subscription,
                payment, "SEPAY", new BigDecimal("10000"), "VND", deadline));
    }

    @Test
    void reusedReferenceWithChangedAmountConflictsWithoutGranting() {
        service.createPaymentLink(11L, 41L);
        OffsetDateTime paidAt = OffsetDateTime.now();
        mockCallbackQueries(List.of(new JSONObject().set("invoiceNo", payment.getInvoiceNo()).set("amount", 9999)
                .set("currency", "VND").set("paidAt", paidAt.toString()).toString()), 0L);
        ServiceException exception = assertThrows(ServiceException.class, () -> service.applyVerifiedPayment(
                payment.getInvoiceNo(), "SEPAY", "transfer-1", new BigDecimal("10000"), "VND", paidAt, "OK", "verified"));
        assertEquals(409, exception.getStatusCode());
        assertNull(subscription.getPaymentReceipt());
        assertEquals("PENDING_PAYMENT", subscription.getStatus());
    }

    @Test
    void anotherPaidPeriodCausesReviewInsteadOfGrantingOverlappingOrder() {
        service.createPaymentLink(11L, 41L);
        mockCallbackQueries(List.of(), 1L);
        service.applyVerifiedPayment(payment.getInvoiceNo(), "SEPAY", "transfer-overlap", new BigDecimal("10000"),
                "VND", OffsetDateTime.now(), "OK", "verified");
        assertEquals("PENDING_PAYMENT", subscription.getStatus());
        assertEquals("OVERLAPPING_PAID_PERIOD", payment.getResultMessage());
        assertEquals("REVIEW", JSONUtil.parseObj(subscription.getPaymentReceipt()).getJSONArray("transactions")
                .getJSONObject(0).getStr("applicationStatus"));
    }

    @Test
    void oldInlineReceiptIsPreservedWhenAddingEvidence() {
        JSONObject object = VehicleSubscriptionPaymentService.receiptEnvelope("{\"bank\":\"old\"}");
        assertEquals("old", object.getJSONObject("legacyReceipt").getStr("bank"));
        assertEquals(0, object.getJSONArray("transactions").size());
        assertEquals("\"legacy-text\"", VehicleSubscriptionPaymentService.receiptEnvelope("\"legacy-text\"")
                .getStr("legacyReceipt"));
    }

    @SuppressWarnings("unchecked")
    private void mockCallbackQueries(List<?> duplicates, Long overlaps) {
        TypedQuery<PaymentEntity> invoice = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(PaymentEntity.class))).thenReturn(invoice);
        when(invoice.setParameter(anyString(), any())).thenReturn(invoice);
        when(invoice.getResultList()).thenReturn(List.of(payment));
        Query nativeQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(0L);
        when(nativeQuery.getResultList()).thenReturn((List) duplicates);
        TypedQuery<Long> overlap = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(overlap);
        when(overlap.setParameter(anyString(), any())).thenReturn(overlap);
        when(overlap.getSingleResult()).thenReturn(overlaps);
    }

    private CreatePaymentResponse response(CreatePaymentRequest request) {
        CreatePaymentResponse response = new CreatePaymentResponse();
        response.setInvoiceNo(request.getInvoiceNo());
        response.setAmount(request.getAmount());
        response.setResultCd("00_000");
        response.setPaymentLink("https://qr.invalid/" + request.getInvoiceNo());
        response.setLinkExptime(java.time.LocalDateTime.now().plusMinutes(10)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        return response;
    }

    private static final class LocalTransactionManager extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) {}
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
