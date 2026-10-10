package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.fee.VerifiedParkingPayment;
import vio.barie.core.dto.parking.PaymentMessage;
import vio.barie.core.dto.payment.MegaPayResponse;
import vio.barie.core.dto.payment.SePayTransaction;
import vio.barie.core.dto.payment.SePayWebhookPayload;
import vio.barie.core.dto.payment.SePayTransactionListResponse;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.common.TicketStatus;
import vio.barie.core.repository.*;
import vio.barie.core.service.mqtt.MQTTPublisher;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.megapay.MegaPayGateway;
import vio.barie.core.service.payment.sepay.SePayGateway;
import vio.barie.core.utils.MegaPaySignatureUtil;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Scoped fee callbacks must bypass all legacy payment/device mutations. */
class ParkingPaymentGatewayTest {
    private PaymentRepository payments;
    private TicketRepository tickets;
    private ParkingMessageService iot;
    private VehicleSubscriptionService subscriptions;
    private ParkingPaymentService parkingPayments;
    private SePayGateway sepay;
    private MegaPayGateway megapay;
    private MegaPayConfig megaConfig;
    private PaymentEntity payment;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        payments = mock(PaymentRepository.class);
        tickets = mock(TicketRepository.class);
        iot = mock(ParkingMessageService.class);
        subscriptions = mock(VehicleSubscriptionService.class);
        parkingPayments = mock(ParkingPaymentService.class);
        ObjectProvider<ParkingPaymentService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(parkingPayments);
        SePayConfig config = new SePayConfig();
        config.setInvoicePrefix("viotech_");
        config.setBankAccount("account-1");
        sepay = spy(new SePayGateway(payments, tickets, config, iot, mock(CalculatorService.class), subscriptions));
        sepay.setParkingPayments(provider);
        megaConfig = new MegaPayConfig();
        megaConfig.setMerId("merchant-1");
        megaConfig.setEncodeKey("test-key");
        megapay = spy(new MegaPayGateway(payments, tickets, megaConfig, iot, subscriptions));
        megapay.setParkingPayments(provider);
        payment = new PaymentEntity();
        payment.setId(90L);
        payment.setInvoiceNo("viotech_12345");
        payment.setAmount(BigDecimal.valueOf(50_000));
        payment.setCurrency("VND");
        payment.setGateway("SEPAY");
        payment.setStatus("PENDING");
        payment.setCallbackType("SESSION");
        JSONObject context = new JSONObject();
        context.set("calculationId", 20L);
        context.set("parkingFeePaymentId", 30L);
        context.set("sessionId", 40L);
        context.set("barrierId", "barrier-1");
        context.set("plateNumber", "51A12345");
        payment.setCallbackContext(context);
        when(payments.findByInvoiceNo(payment.getInvoiceNo())).thenReturn(Optional.of(payment));
    }

    @Test
    void sepayAuthenticatedEvidenceUsesActualMoneyAndOffsetTimeThenOnlyCoreOwnsSettlement() {
        doReturn(transaction()).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        Instant before = Instant.now();
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        VerifiedParkingPayment evidence = evidence();
        assertEquals(payment.getId(), evidence.paymentId());
        assertEquals("SEPAY", evidence.source());
        assertEquals("tx-1", evidence.reference());
        assertEquals(payment.getInvoiceNo(), evidence.invoiceNo());
        assertEquals(50_000L, evidence.amountVnd());
        assertEquals(Instant.parse("2026-10-10T03:00:00Z"), evidence.paidAt());
        assertFalse(evidence.receivedAt().isBefore(before));
        assertEquals("PAID", evidence.status());
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutations();
    }

    @Test
    void sepayActualAmountDifferentFromQuoteReachesCoreForReview() {
        SePayTransaction transaction = transaction();
        transaction.setAmountIn(BigDecimal.valueOf(49_000));
        SePayWebhookPayload webhook = webhook();
        webhook.setTransferAmount(BigDecimal.valueOf(49_000));
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        sepay.handleCallback(JSONUtil.toJsonStr(webhook));
        assertEquals(49_000L, evidence().amountVnd());
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID", "REVIEW_REQUIRED"})
    void sepayAlwaysRoutesReplayThroughCoreWithoutOverwritingPayment(String status) {
        payment.setStatus(status);
        payment.setSepayTxId("original-tx");
        doReturn(transaction()).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        verify(parkingPayments).confirmVerifiedPayment(any());
        assertEquals(status, payment.getStatus());
        assertEquals("original-tx", payment.getSepayTxId());
        verifyNoLegacyMutations();
    }

    @Test
    void sepayUnsignedWebhookDoesNotBypassAuthenticatedLookup() {
        doReturn(null).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(webhook())));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void sepayRejectsWrongBeneficiaryEvenWithMatchingProviderTransactionId() {
        SePayTransaction transaction = transaction();
        transaction.setAccountNumber("wrong-account");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(webhook())));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void sepayRejectsUnverifiedTransactionMoneyTampering() {
        SePayWebhookPayload payload = webhook();
        payload.setTransferAmount(BigDecimal.ONE);
        doReturn(transaction()).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(payload)));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void sepayRejectsWrongInvoiceFromAuthenticatedLookup() {
        SePayTransaction transaction = transaction();
        transaction.setCode("viotech_777");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(webhook())));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODE", "MEMO", "MULTIPLE", "WEBHOOK_MEMO"})
    void sepayConflictingInvoiceFieldsCannotConsumeAnotherCalculationTransaction(String conflict) {
        SePayTransaction transaction = transaction();
        SePayWebhookPayload payload = webhook();
        transaction.setTransactionContent(payment.getInvoiceNo());
        if ("CODE".equals(conflict)) transaction.setCode("viotech_777");
        if ("MEMO".equals(conflict)) transaction.setTransactionContent("viotech_777");
        if ("MULTIPLE".equals(conflict)) transaction.setTransactionContent(payment.getInvoiceNo() + " viotech_777");
        if ("WEBHOOK_MEMO".equals(conflict)) payload.setContent("viotech_777");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(payload)));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void sepayNumericWebhookIdIsBoundToAuthenticatedUuidThroughExactBankReferenceAndAccount() {
        SePayTransaction transaction = transaction();
        transaction.setId("provider-uuid");
        transaction.setReferenceNumber("FT-001");
        SePayWebhookPayload payload = webhook();
        payload.setId("123456");
        payload.setReferenceCode("FT-001");
        payload.setAccountNumber("account-1");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        sepay.handleCallback(JSONUtil.toJsonStr(payload));
        assertEquals("provider-uuid", evidence().reference());
        verifyNoLegacyMutations();
    }

    @Test
    void sepayCrossVersionIdentityCollisionWithWrongBankReferenceIsRejected() {
        SePayTransaction transaction = transaction();
        transaction.setId("provider-uuid");
        transaction.setReferenceNumber("FT-001");
        SePayWebhookPayload payload = webhook();
        payload.setId("123456");
        payload.setReferenceCode("FT-other");
        payload.setAccountNumber("account-1");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(payload)));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sepayAmbiguousAuthenticatedProviderResultsAreNeverTurnedIntoFeeEvidence() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SePayConfig config = new SePayConfig();
            config.setInvoicePrefix("viotech_"); config.setBankAccount("account-1");
            config.setApiBaseUrl(server.url("/").toString().replaceAll("/$", ""));
            config.setApiToken("api-test-token");
            SePayGateway gateway = new SePayGateway(payments, tickets, config, iot,
                    mock(CalculatorService.class), subscriptions);
            ObjectProvider<ParkingPaymentService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(parkingPayments);
            gateway.setParkingPayments(provider);
            SePayTransactionListResponse response = new SePayTransactionListResponse();
            response.setStatus("success"); response.setData(List.of(transaction(), transaction()));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(new ObjectMapper().writeValueAsString(response)));
            assertThrows(ServiceException.class, () -> gateway.handleCallback(JSONUtil.toJsonStr(webhook())));
            verifyNoInteractions(parkingPayments);
            verifyNoLegacyMutations();
        }
    }

    @Test
    void sepayRejectsFractionalVndWithoutCreatingPaymentEvidence() {
        SePayTransaction transaction = transaction();
        transaction.setAmountIn(new BigDecimal("50000.25"));
        SePayWebhookPayload payload = webhook();
        payload.setTransferAmount(transaction.getAmountIn());
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(payload)));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void sepayUntrustedTimezoneIsNeverReplacedWithServerTime() {
        SePayTransaction transaction = transaction();
        transaction.setTransactionDate("2026-10-10 10:00:00");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        assertNull(evidence().paidAt());
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEGAPAY", "CASH"})
    void sepayCannotConsumeAnotherGatewayIntent(String gateway) {
        payment.setGateway(gateway);
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(webhook())));
        verify(sepay, never()).findVerifiedSubscriptionTransaction(anyString(), any());
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void megapaySignedSuccessLeavesUnknownPaymentTimeForCoreReview() {
        payment.setGateway("MEGAPAY");
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse("00_000")));
        VerifiedParkingPayment evidence = evidence();
        assertEquals("MEGAPAY", evidence.source());
        assertEquals("mega-tx-1", evidence.reference());
        assertEquals(50_000L, evidence.amountVnd());
        assertEquals("PAID", evidence.status());
        assertNull(evidence.paidAt());
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "99_001"})
    void megapaySignedNonSuccessOnlyRoutesAnObservationNeverExecutesSuccessBranch(String code) {
        payment.setGateway("MEGAPAY");
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse(code)));
        assertEquals("99".equals(code) ? "PENDING" : "FAILED", evidence().status());
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "99_001"})
    void megapaySignedUnsuccessfulOutcomeDoesNotNeedAReceiptTransactionIdOrReceivedMoney(String code) {
        payment.setGateway("MEGAPAY");
        MegaPayResponse response = megaResponse(code);
        response.setTrxId(null); response.setAmount("0"); response.setCurrency(null);
        sign(response);
        megapay.handleCallback(JSONUtil.toJsonStr(response));
        VerifiedParkingPayment observed = evidence();
        assertEquals("99".equals(code) ? "PENDING" : "FAILED", observed.status());
        assertNull(observed.reference()); assertNull(observed.amountVnd());
        assertEquals("VND", observed.currency());
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutations();
    }

    @Test
    void megapayInvalidSignatureCannotReachCore() {
        payment.setGateway("MEGAPAY");
        MegaPayResponse response = megaResponse("00_000");
        response.setMerchantToken("invalid");
        megapay.handleCallback(JSONUtil.toJsonStr(response));
        verifyNoInteractions(parkingPayments, payments);
        verifyNoLegacyMutations();
    }

    @Test
    void megapaySignedInvoiceRedirectionCannotReachCore() {
        payment.setGateway("MEGAPAY");
        MegaPayResponse response = megaResponse("00_000");
        response.setMerTrxId("viotech_777");
        sign(response);
        assertThrows(ServiceException.class, () -> megapay.handleCallback(JSONUtil.toJsonStr(response)));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void megapayUnsignedCurrencyCannotReachCore() {
        payment.setGateway("MEGAPAY");
        MegaPayResponse response = megaResponse("00_000");
        response.setCurrency("USD");
        assertThrows(ServiceException.class, () -> megapay.handleCallback(JSONUtil.toJsonStr(response)));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID", "REVIEW_REQUIRED"})
    void scopedInquiryOnlyReportsCommittedLocalStatusAndDoesNotCallEitherProvider(String status) {
        payment.setStatus(status);
        var sepayResult = sepay.inquiryByInvoiceNo(payment.getInvoiceNo());
        var megaResult = megapay.inquiryByInvoiceNo(payment.getInvoiceNo());
        assertEquals(status, sepayResult.getStatus());
        assertEquals(status, megaResult.getStatus());
        assertNull(sepayResult.getSubscriptionId());
        assertNull(sepayResult.getMetadata());
        assertNull(megaResult.getMetadata());
        verify(sepay, never()).findSePayTransactionByInvoice(anyString());
        verify(megapay, never()).inquiryByInvoiceNo(any(Map.class));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @ParameterizedTest
    @CsvSource({"SESSION,99_001", "SESSION,99", "TICKET,99_001", "TICKET,99"})
    void legacyMegapayFailureAndPendingDoNotSendSuccessOrActivateTicket(String callbackType, String code) {
        payment.setCallbackType(callbackType);
        payment.setCallbackContext(new JSONObject());
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse(code)));
        assertEquals("99".equals(code) ? "PENDING" : "FAILED", payment.getStatus());
        verify(payments).save(payment);
        verifyNoInteractions(iot, tickets, parkingPayments, subscriptions);
    }

    @Test
    void legacyMegapaySuccessfulSessionRetainsItsExistingDeviceFlow() {
        payment.setCallbackContext(new JSONObject(Map.of("barrierId", "barrier-1", "sessionId", 40L,
                "plateNumber", "51A12345")));
        doReturn(null).when(megapay).inquiryByInvoiceNo(payment.getInvoiceNo());
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse("00_000")));
        assertEquals("PAID", payment.getStatus());
        verify(iot).publishPaymentConfirmed("barrier-1", 40L, BigDecimal.valueOf(50_000), payment.getInvoiceNo());
        verify(iot).publishPaymentSuccess("barrier-1", 40L, "51A12345");
        verify(payments).save(payment);
        verifyNoInteractions(tickets, parkingPayments, subscriptions);
    }

    @Test
    void legacyMegapaySuccessfulTicketRetainsItsExistingActivationFlow() {
        payment.setCallbackType("TICKET");
        payment.setCallbackContext(new JSONObject(Map.of("ticketId", 45L)));
        TicketEntity ticket = new TicketEntity();
        ticket.setStatus(TicketStatus.DEAD);
        when(tickets.findById(45L)).thenReturn(Optional.of(ticket));
        doReturn(null).when(megapay).inquiryByInvoiceNo(payment.getInvoiceNo());
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse("00_000")));
        assertEquals("PAID", payment.getStatus());
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
        verify(tickets).save(ticket);
        verify(payments).save(payment);
        verifyNoInteractions(iot, parkingPayments, subscriptions);
    }

    @Test
    void partialScopedContextCannotFallThroughLegacyEvenWhenCoreIsNotConfigured() {
        payment.setGateway("MEGAPAY");
        payment.getCallbackContext().remove("parkingFeePaymentId");
        MegaPayGateway noCore = new MegaPayGateway(payments, tickets, megaConfig, iot, subscriptions);
        assertThrows(ServiceException.class, () -> noCore.handleCallback(JSONUtil.toJsonStr(megaResponse("00_000"))));
        verifyNoInteractions(parkingPayments);
        verifyNoLegacyMutations();
    }

    @Test
    void scopedCashDeviceAckCannotUseClientAmountOrBarrierToMarkPaidOrOpenGate() {
        ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
        when(sessions.findById(40L)).thenReturn(Optional.of(new ParkingSessionEntity()));
        when(payments.findLatestBySessionId("40")).thenReturn(Optional.of(payment));
        MQTTPublisher publisher = mock(MQTTPublisher.class);
        ParkingMessageService messages = new ParkingMessageService(mock(ParkingMessageRepository.class),
                mock(ParkingDeviceRepository.class), mock(BarrierRepository.class), sessions, payments,
                new ObjectMapper(), publisher);
        PaymentMessage acknowledgement = new PaymentMessage();
        acknowledgement.setSessionId("40");
        acknowledgement.setBarrierId("attacker-barrier");
        acknowledgement.setAmountDue(1);
        acknowledgement.setAmountPaid(1);
        acknowledgement.setPlate("attacker-plate");
        assertNull(messages.handleCashPayment(acknowledgement));
        assertEquals("PENDING", payment.getStatus());
        verify(payments, never()).save(any());
        verifyNoInteractions(publisher, parkingPayments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"calculationId", "parkingFeePaymentId", "subscriptionId"})
    void partialScopedOrPaidContextCannotReplayCashBarrierCommand(String scopedKey) {
        payment.setStatus("PAID");
        payment.setCallbackContext(new JSONObject(Map.of("sessionId",40L,scopedKey,1L)));
        ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
        when(sessions.findById(40L)).thenReturn(Optional.of(new ParkingSessionEntity()));
        when(payments.findLatestBySessionId("40")).thenReturn(Optional.of(payment));
        MQTTPublisher publisher = mock(MQTTPublisher.class);
        ParkingMessageService messages = new ParkingMessageService(mock(ParkingMessageRepository.class),
                mock(ParkingDeviceRepository.class),mock(BarrierRepository.class),sessions,payments,new ObjectMapper(),publisher);
        PaymentMessage acknowledgement = new PaymentMessage();
        acknowledgement.setSessionId("40");
        acknowledgement.setBarrierId("attacker-barrier");
        acknowledgement.setPlate("attacker-plate");
        assertNull(messages.handleCashPayment(acknowledgement));
        verify(payments,never()).save(any());
        verifyNoInteractions(publisher,parkingPayments);
    }

    private VerifiedParkingPayment evidence() {
        ArgumentCaptor<VerifiedParkingPayment> captor = ArgumentCaptor.forClass(VerifiedParkingPayment.class);
        verify(parkingPayments).confirmVerifiedPayment(captor.capture());
        return captor.getValue();
    }

    private void verifyNoLegacyMutations() {
        verify(payments, never()).save(any());
        verify(payments, never()).saveAndFlush(any());
        verifyNoInteractions(tickets, iot, subscriptions);
    }

    private SePayWebhookPayload webhook() {
        SePayWebhookPayload webhook = new SePayWebhookPayload();
        webhook.setId("tx-1");
        webhook.setCode(payment.getInvoiceNo());
        webhook.setTransferType("in");
        webhook.setTransferAmount(BigDecimal.valueOf(50_000));
        return webhook;
    }

    private SePayTransaction transaction() {
        SePayTransaction transaction = new SePayTransaction();
        transaction.setId("tx-1");
        transaction.setCode(payment.getInvoiceNo());
        transaction.setTransferType("in");
        transaction.setAmountIn(BigDecimal.valueOf(50_000));
        transaction.setAmountOut(BigDecimal.ZERO);
        transaction.setAccountNumber("account-1");
        transaction.setTransactionDate("2026-10-10T10:00:00+07:00");
        return transaction;
    }

    private MegaPayResponse megaResponse(String code) {
        MegaPayResponse response = new MegaPayResponse();
        response.setInvoiceNo(payment.getInvoiceNo());
        response.setMerTrxId(payment.getInvoiceNo());
        response.setTrxId("mega-tx-1");
        response.setMerId(megaConfig.getMerId());
        response.setResultCd(code);
        response.setTimeStamp("1791601200000");
        response.setAmount("50000");
        response.setCurrency("VND");
        sign(response);
        return response;
    }

    private void sign(MegaPayResponse response) {
        response.setMerchantToken(MegaPaySignatureUtil.sha256(response.getResultCd() + response.getTimeStamp()
                + response.getMerTrxId() + response.getTrxId() + megaConfig.getMerId()
                + response.getAmount() + megaConfig.getEncodeKey()));
    }
}

