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
import org.mockito.ArgumentCaptor;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.MegaPayResponse;
import vio.barie.core.dto.payment.SePayTransaction;
import vio.barie.core.dto.payment.SePayTransactionListResponse;
import vio.barie.core.dto.payment.SePayWebhookPayload;
import vio.barie.core.dto.subscription.VerifiedSubscriptionPayment;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.megapay.MegaPayGateway;
import vio.barie.core.service.payment.sepay.SePayGateway;
import vio.barie.core.utils.MegaPaySignatureUtil;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated gateway tests: no network, payment credentials, database or IoT connections. */
class VehicleSubscriptionGatewayTest {
    private PaymentRepository payments;
    private TicketRepository tickets;
    private ParkingMessageService iot;
    private VehicleSubscriptionService subscriptions;
    private SePayGateway sepay;
    private MegaPayGateway megapay;
    private MegaPayConfig megaConfig;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        tickets = mock(TicketRepository.class);
        iot = mock(ParkingMessageService.class);
        subscriptions = mock(VehicleSubscriptionService.class);
        SePayConfig config = new SePayConfig();
        config.setInvoicePrefix("viotech_");
        config.setBankAccount("test-account");
        sepay = spy(new SePayGateway(payments, tickets, config, iot, mock(CalculatorService.class), subscriptions));
        megaConfig = new MegaPayConfig();
        megaConfig.setMerId("test-merchant");
        megaConfig.setEncodeKey("unit-test-signature-key");
        megapay = new MegaPayGateway(payments, tickets, megaConfig, iot, subscriptions);
        payment = new PaymentEntity();
        payment.setId(9L);
        payment.setInvoiceNo("viotech_12345");
        payment.setAmount(BigDecimal.valueOf(50_000));
        payment.setCurrency("VND");
        payment.setStatus("PENDING");
        payment.setCallbackType(PaymentCallbackType.SUBSCRIPTION.name());
        JSONObject context = new JSONObject();
        context.set("subscriptionId", 7L);
        payment.setCallbackContext(context);
        when(payments.findByInvoiceNo(payment.getInvoiceNo())).thenReturn(Optional.of(payment));
    }

    @Test
    void sepayUsesAuthenticatedInquiryTimestampAndRoutesOnlyToSubscription() {
        doReturn(verifiedTransaction()).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        VerifiedSubscriptionPayment evidence = evidence();
        assertEquals(7L, evidence.subscriptionId());
        assertEquals(9L, evidence.paymentId());
        assertEquals("SEPAY", evidence.source());
        assertEquals("tx-1", evidence.reference());
        assertEquals(50_000L, evidence.amountVnd());
        assertEquals(Instant.parse("2026-10-09T03:00:00Z"), evidence.paidAt());
        assertNull(evidence.receivedAt());
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutation();
    }

    @Test
    void sepayUnverifiedWebhookCannotGrant() {
        doReturn(null).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(webhook())));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @Test
    void sepayWebhookAmountTamperingCannotGrant() {
        doReturn(verifiedTransaction()).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        SePayWebhookPayload payload = webhook();
        payload.setTransferAmount(BigDecimal.valueOf(1));
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(payload)));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @Test
    void sepayDifferentBankAccountCannotGrant() {
        SePayTransaction transaction = verifiedTransaction();
        transaction.setAccountNumber("other-account");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(webhook())));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @Test
    void sepayActualAmountMismatchIsAuditedByCoreInsteadOfReplacedByQuotedAmount() {
        SePayTransaction transaction = verifiedTransaction();
        transaction.setAmountIn(BigDecimal.valueOf(49_000));
        SePayWebhookPayload payload = webhook();
        payload.setTransferAmount(BigDecimal.valueOf(49_000));
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        sepay.handleCallback(JSONUtil.toJsonStr(payload));
        assertEquals(49_000L, evidence().amountVnd());
        verifyNoLegacyMutation();
    }

    @Test
    void sepayUnknownTimezonePreservesReceiptWithoutGuessingPaymentTime() {
        SePayTransaction transaction = verifiedTransaction();
        transaction.setTransactionDate("2026-10-09 10:00:00");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        assertNull(evidence().paidAt());
        verifyNoLegacyMutation();
    }

    @Test
    void sepayReplayOfPaidIntentStillRoutesThroughCoreDeduplicationWithoutOverwritingReference() {
        payment.setStatus("PAID");
        payment.setSepayTxId("already-applied-tx");
        doReturn(verifiedTransaction()).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any(SePayWebhookPayload.class));
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        verify(subscriptions).confirmVerifiedPayment(any());
        assertEquals("already-applied-tx", payment.getSepayTxId());
        verifyNoLegacyMutation();
    }

    @Test
    void sepayNumericWebhookIdMapsToApiUuidUsingBankReferenceAndAccount() {
        SePayTransaction transaction = verifiedTransaction();
        transaction.setId("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        transaction.setReferenceNumber("FT-123");
        SePayWebhookPayload payload = webhook();
        payload.setId("92704");
        payload.setReferenceCode("FT-123");
        payload.setAccountNumber("test-account");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        sepay.handleCallback(JSONUtil.toJsonStr(payload));
        VerifiedSubscriptionPayment evidence = evidence();
        assertEquals(transaction.getId(), evidence.reference());
        assertEquals("92704", evidence.metadata().get("webhookTransactionId"));
        verifyNoLegacyMutation();
    }

    @Test
    void sepayCrossVersionMappingRejectsWrongBankReference() {
        SePayTransaction transaction = verifiedTransaction();
        transaction.setId("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        transaction.setReferenceNumber("FT-123");
        SePayWebhookPayload payload = webhook();
        payload.setId("92704");
        payload.setReferenceCode("FT-other");
        payload.setAccountNumber("test-account");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        assertThrows(ServiceException.class, () -> sepay.handleCallback(JSONUtil.toJsonStr(payload)));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @Test
    void sepayVerifiesInvoiceFromAuthenticatedTransactionMemoWhenApiCodeIsNull() {
        SePayTransaction transaction = verifiedTransaction();
        transaction.setCode(null);
        transaction.setTransactionContent(payment.getInvoiceNo() + " Phi gui xe");
        doReturn(transaction).when(sepay).findVerifiedSubscriptionTransaction(eq(payment.getInvoiceNo()), any());
        sepay.handleCallback(JSONUtil.toJsonStr(webhook()));
        assertEquals("tx-1", evidence().reference());
        verifyNoLegacyMutation();
    }

    @Test
    void sepayOutboundNotificationCannotGrant() {
        SePayWebhookPayload payload = webhook();
        payload.setTransferType("out");
        sepay.handleCallback(JSONUtil.toJsonStr(payload));
        verifyNoInteractions(subscriptions, payments);
        verifyNoLegacyMutation();
    }

    @Test
    void authenticatedSePayLookupRequestsOffsetTimestampAndMatchesExactId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SePayConfig config = new SePayConfig();
            config.setApiBaseUrl(server.url("/").toString().replaceAll("/$", ""));
            config.setApiToken("test-token");
            SePayGateway gateway = new SePayGateway(payments, tickets, config, iot,
                    mock(CalculatorService.class), subscriptions);
            SePayTransaction unknownCode = new SePayTransaction();
            unknownCode.setId("ignore-null-code");
            SePayTransaction wrongId = verifiedTransaction();
            wrongId.setId("other-tx");
            SePayTransactionListResponse body = new SePayTransactionListResponse();
            body.setStatus("success");
            body.setData(List.of(unknownCode, wrongId, verifiedTransaction()));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(new ObjectMapper().writeValueAsString(body)));
            SePayTransaction result = gateway.findVerifiedSubscriptionTransaction(payment.getInvoiceNo(), webhook());
            assertNotNull(result);
            assertEquals("tx-1", result.getId());
            assertEquals("2026-10-09T10:00:00+07:00", result.getTransactionDate());
            assertEquals(BigDecimal.valueOf(50_000), result.getAmountIn());
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("Bearer test-token", request.getHeader("Authorization"));
            assertTrue(request.getPath().contains("timestamp_format=iso8601"));
            assertTrue(request.getPath().contains("q=viotech_12345"));
        }
    }

    @Test
    void legacySePayWrapperTransactionsAliasPreservesSnakeCaseValues() throws Exception {
        String body = """
                {"status":200,"transactions":[{"id":"123","transaction_date":"2026-10-09 10:00:00",
                "amount_in":"50000.00","code":"viotech_12345"}]}
                """;
        SePayTransactionListResponse response = new ObjectMapper().readValue(body, SePayTransactionListResponse.class);
        assertEquals("200", response.getStatus());
        assertEquals("123", response.getData().getFirst().getId());
        assertEquals("2026-10-09 10:00:00", response.getData().getFirst().getTransactionDate());
        assertEquals(new BigDecimal("50000.00"), response.getData().getFirst().getAmountIn());
    }

    @Test
    void megapaySignedSuccessfulCallbackRoutesInternalEvidenceWithUnknownPaidTime() {
        MegaPayResponse response = megaResponse("00_000");
        megapay.handleCallback(JSONUtil.toJsonStr(response));
        VerifiedSubscriptionPayment evidence = evidence();
        assertEquals("MEGAPAY", evidence.source());
        assertEquals("mega-tx-1", evidence.reference());
        assertEquals(50_000L, evidence.amountVnd());
        assertNull(evidence.paidAt());
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutation();
    }

    @Test
    void megapayDifferentActualAmountIsPassedToCoreForReceiptReview() {
        MegaPayResponse response = megaResponse("00_000");
        response.setAmount("49000");
        sign(response);
        megapay.handleCallback(JSONUtil.toJsonStr(response));
        assertEquals(49_000L, evidence().amountVnd());
        verifyNoLegacyMutation();
    }

    @Test
    void megapayUnsignedCurrencyTamperingIsRejectedWithoutChangingSubscriptionState() {
        MegaPayResponse response = megaResponse("00_000");
        response.setCurrency("USD");
        assertThrows(ServiceException.class, () -> megapay.handleCallback(JSONUtil.toJsonStr(response)));
        verifyNoInteractions(subscriptions);
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutation();
    }

    @Test
    void megapaySignedFailureDoesNotGrantOrExecuteLegacyTicketFlow() {
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse("99_001")));
        verifyNoInteractions(subscriptions);
        assertEquals("PENDING", payment.getStatus());
        verifyNoLegacyMutation();
    }

    @Test
    void megapaySignedPendingDoesNotGrant() {
        megapay.handleCallback(JSONUtil.toJsonStr(megaResponse("99")));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @Test
    void megapayInvalidSignatureCannotGrant() {
        MegaPayResponse response = megaResponse("00_000");
        response.setMerchantToken("invalid");
        megapay.handleCallback(JSONUtil.toJsonStr(response));
        verifyNoInteractions(subscriptions, payments);
        verifyNoLegacyMutation();
    }

    @Test
    void megapayUnsignedInvoiceRedirectionIsRejectedEvenWithValidSignature() {
        MegaPayResponse response = megaResponse("00_000");
        response.setMerTrxId("viotech_other");
        sign(response);
        assertThrows(ServiceException.class, () -> megapay.handleCallback(JSONUtil.toJsonStr(response)));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REVIEW_REQUIRED", "PAID"})
    void sepayInquiryReportsLocalVerifiedOutcomeWithoutCallingProvider(String status) {
        payment.setStatus(status);
        payment.setResultCode("REVIEW_REQUIRED".equals(status) ? "REVIEW_REQUIRED" : null);
        payment.setResultMessage("REVIEW_REQUIRED".equals(status) ? "AMOUNT_MISMATCH" : null);
        payment.getCallbackContext().set("providerSecret", "must-not-be-returned");

        var result = sepay.inquiryByInvoiceNo(payment.getInvoiceNo());

        assertEquals(status, result.getStatus());
        assertEquals(7L, result.getSubscriptionId());
        assertEquals("SUBSCRIPTION", result.getCallbackType());
        assertEquals(payment.getAmount(), result.getAmount());
        assertEquals(payment.getResultMessage(), result.getResultMessage());
        assertNull(result.getMetadata());
        verify(sepay, never()).findSePayTransactionByInvoice(anyString());
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REVIEW_REQUIRED", "PAID"})
    void megapayInquiryReportsLocalVerifiedOutcomeWithoutCallingProvider(String status) {
        payment.setStatus(status);
        payment.setResultCode("REVIEW_REQUIRED".equals(status) ? "REVIEW_REQUIRED" : null);
        payment.setResultMessage("REVIEW_REQUIRED".equals(status) ? "PAYMENT_TIME_UNKNOWN" : null);
        MegaPayGateway gateway = spy(megapay);

        var result = gateway.inquiryByInvoiceNo(payment.getInvoiceNo());

        assertEquals(status, result.getStatus());
        assertEquals(7L, result.getSubscriptionId());
        assertEquals(payment.getAmount(), result.getAmount());
        assertEquals(payment.getResultMessage(), result.getResultMessage());
        assertNull(result.getMetadata());
        verify(gateway, never()).inquiryByInvoiceNo(any(java.util.Map.class));
        verifyNoInteractions(subscriptions);
        verifyNoLegacyMutation();
    }

    @Test
    void sepayLegacySessionInquiryKeepsItsProviderFlow() {
        payment.setCallbackType("SESSION");
        doReturn(verifiedTransaction()).when(sepay).findSePayTransactionByInvoice(payment.getInvoiceNo());

        var result = sepay.inquiryByInvoiceNo(payment.getInvoiceNo());

        assertEquals("PAID", result.getStatus());
        assertNull(result.getSubscriptionId());
        verify(sepay).findSePayTransactionByInvoice(payment.getInvoiceNo());
        verifyNoInteractions(subscriptions);
    }

    @Test
    void megapayLegacySessionInquiryKeepsItsProviderUpdate() {
        payment.setCallbackType("SESSION");
        MegaPayGateway gateway = spy(megapay);
        MegaPayResponse provider = megaResponse("00_000");
        provider.setStatus("PAID");
        doReturn(provider).when(gateway).inquiryByInvoiceNo(any(java.util.Map.class));

        var result = gateway.inquiryByInvoiceNo(payment.getInvoiceNo());

        assertEquals("PAID", result.getStatus());
        assertEquals("PAID", payment.getStatus());
        verify(payments).save(payment);
        verifyNoInteractions(subscriptions);
    }

    private VerifiedSubscriptionPayment evidence() {
        ArgumentCaptor<VerifiedSubscriptionPayment> captor = ArgumentCaptor.forClass(VerifiedSubscriptionPayment.class);
        verify(subscriptions).confirmVerifiedPayment(captor.capture());
        return captor.getValue();
    }

    private void verifyNoLegacyMutation() {
        verify(payments, never()).save(any());
        verify(payments, never()).saveAndFlush(any());
        verifyNoInteractions(tickets, iot);
    }

    private SePayWebhookPayload webhook() {
        SePayWebhookPayload payload = new SePayWebhookPayload();
        payload.setId("tx-1");
        payload.setCode(payment.getInvoiceNo());
        payload.setTransferType("in");
        payload.setTransferAmount(BigDecimal.valueOf(50_000));
        payload.setTransactionDate("2026-10-09 11:59:00"); // deliberately ignored
        return payload;
    }

    private SePayTransaction verifiedTransaction() {
        SePayTransaction transaction = new SePayTransaction();
        transaction.setId("tx-1");
        transaction.setCode(payment.getInvoiceNo());
        transaction.setTransferType("in");
        transaction.setAmountIn(BigDecimal.valueOf(50_000));
        transaction.setAmountOut(BigDecimal.ZERO);
        transaction.setAccountNumber("test-account");
        transaction.setTransactionDate("2026-10-09T10:00:00+07:00");
        return transaction;
    }

    private MegaPayResponse megaResponse(String code) {
        MegaPayResponse response = new MegaPayResponse();
        response.setInvoiceNo(payment.getInvoiceNo());
        response.setMerTrxId(payment.getInvoiceNo());
        response.setTrxId("mega-tx-1");
        response.setMerId(megaConfig.getMerId());
        response.setResultCd(code);
        response.setTimeStamp("1791514800000");
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
