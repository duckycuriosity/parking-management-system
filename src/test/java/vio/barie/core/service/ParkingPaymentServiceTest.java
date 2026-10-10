package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.VerifiedParkingPayment;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.*;
import vio.barie.core.event.ParkingFeeSettled;
import vio.barie.core.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParkingPaymentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private ParkingFeeService fees;
    private PaymentService payments;
    private PaymentRepository intents;
    private ParkingFeePaymentRepository attempts;
    private ParkingFeeTransactionKeyRepository keys;
    private EntityManager entityManager;
    private ApplicationEventPublisher events;
    private ParkingPaymentService service;
    private FeeCalculationEntity calculation;
    private ParkingFeePaymentEntity attempt;
    private PaymentEntity intent;
    private ParkingSessionEntity session;
    private Map<ParkingFeeTransactionKeyId, ParkingFeeTransactionKeyEntity> observed;

    @BeforeEach void prepare() {
        fees = mock(ParkingFeeService.class); payments = mock(PaymentService.class);
        intents = mock(PaymentRepository.class); attempts = mock(ParkingFeePaymentRepository.class);
        keys = mock(ParkingFeeTransactionKeyRepository.class); entityManager = mock(EntityManager.class);
        events = mock(ApplicationEventPublisher.class); observed = new HashMap<>();
        service = new ParkingPaymentService(fees, payments, intents, attempts, keys, entityManager, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), events, "sepay");
        calculation = new FeeCalculationEntity(); calculation.setId(100L); calculation.setSessionId(1L);
        calculation.setTotalVnd(30000); calculation.setValidUntil(NOW.plusSeconds(600));
        calculation.setFeeDetail(mapper.createObjectNode().put("timezone", "Asia/Ho_Chi_Minh")
                .put("sessionId", 1L).put("vehicleId", 2L).put("parkingLotId", 3L));
        attempt = new ParkingFeePaymentEntity(); attempt.setId(9L); attempt.setCalculationId(100L);
        attempt.setPaymentId(44L); attempt.setRequestKey("request-A"); attempt.setAmountVnd(30000);
        attempt.setRequestedAt(NOW.minusSeconds(60)); attempt.setReceipt(mapper.createArrayNode());
        intent = new PaymentEntity(); intent.setId(44L); intent.setInvoiceNo("INV001");
        intent.setAmount(BigDecimal.valueOf(30000)); intent.setCurrency("VND"); intent.setStatus("PENDING");
        intent.setGateway("SEPAY"); intent.setCallbackType("SESSION");
        intent.setCallbackContext(new JSONObject().set("calculationId", 100L).set("parkingFeePaymentId", 9L)
                .set("sessionId", 1L).set("vehicleId", 2L).set("parkingLotId", 3L));
        session = new ParkingSessionEntity(); session.setId(1L);
        VehicleEntity vehicle = new VehicleEntity(); vehicle.setId(2L); vehicle.setPlateNumber("30A12345"); session.setVehicle(vehicle);
        ParkingLotEntity lot = new ParkingLotEntity(); lot.setId(3L); session.setParkingLot(lot);
        when(fees.lockForSettlement(100L)).thenReturn(calculation);
        when(intents.findById(44L)).thenReturn(Optional.of(intent)); when(intents.lockById(44L)).thenReturn(Optional.of(intent));
        when(attempts.findByPaymentId(44L)).thenReturn(Optional.of(attempt)); when(attempts.lockById(9L)).thenReturn(Optional.of(attempt));
        when(entityManager.find(ParkingSessionEntity.class, 1L)).thenReturn(session);
        when(keys.findById(any())).thenAnswer(call -> Optional.ofNullable(observed.get(call.getArgument(0))));
        doAnswer(call -> {
            ParkingFeeTransactionKeyEntity key = call.getArgument(0);
            observed.put(new ParkingFeeTransactionKeyId(key.getSource(), key.getReference()), key); return null;
        }).when(entityManager).persist(any(ParkingFeeTransactionKeyEntity.class));
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private VerifiedParkingPayment paid(String reference, long amount, Instant paidAt) {
        return new VerifiedParkingPayment(44L, "SEPAY", reference, "INV001", amount, "VND", paidAt, NOW,
                "PAID", Map.of("verified", true));
    }

    @Test void successfulSettlementEmitsOnlyAfterCommit() {
        var result = service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        assertEquals("APPLIED", result.applicationStatus()); assertEquals("SETTLED", calculation.getStatus());
        assertEquals("PAID", intent.getStatus()); assertEquals(1, attempt.getReceipt().size());
        assertEquals(LocalDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC), intent.getPaidAt());
        verifyNoInteractions(events);
        TransactionSynchronizationUtils.triggerAfterCommit(); verify(events).publishEvent(any(ParkingFeeSettled.class));
    }

    @Test void repeatedTransactionDoesNotAppendOrSettleAgain() {
        service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        assertEquals(1, attempt.getReceipt().size()); assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    }

    @Test void actualSecondTransferIsRetainedForReviewWithoutOverwritingFirst() {
        service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        var result = service.confirmVerifiedPayment(paid("TX002", 30000, NOW.minusSeconds(5)));
        assertEquals("ADDITIONAL_TRANSFER", result.applicationReason()); assertEquals("PAID", attempt.getStatus());
        assertEquals("TX001", intent.getReferenceCode()); assertEquals(2, attempt.getReceipt().size());
        assertEquals("REVIEW_REQUIRED", attempt.getReceipt().get(1).path("applicationStatus").asText());
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        assertEquals("ADDITIONAL_TRANSFER", service.confirmVerifiedPayment(paid("TX002", 30000, NOW.minusSeconds(5))).applicationReason());
    }

    @Test void differentEvidenceWithSameReferenceIsRejected() {
        service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.confirmVerifiedPayment(paid("TX001", 60000, NOW.minusSeconds(10))));
        assertEquals("FEE_TRANSACTION_REFERENCE_CONFLICT", failure.getCode()); assertEquals(1, attempt.getReceipt().size());
    }

    @Test void amountMismatchStoresActualMoneyButGrantsNothing() {
        var result = service.confirmVerifiedPayment(paid("TX001", 60000, NOW.minusSeconds(10)));
        assertEquals("AMOUNT_MISMATCH", result.applicationReason()); assertEquals("QUOTED", calculation.getStatus());
        assertEquals(60000, attempt.getReceipt().get(0).path("amountVnd").asLong()); verifyNoInteractions(events);
    }

    @Test void missingTrustedTimeRequiresReview() {
        var result = service.confirmVerifiedPayment(paid("TX001", 30000, null));
        assertEquals("PAYMENT_TIME_UNKNOWN", result.applicationReason()); assertEquals("QUOTED", calculation.getStatus());
    }

    @Test void laterExactPaymentCannotSilentlyReactivateAnAttemptUnderReview() {
        service.confirmVerifiedPayment(paid("TX001", 10000, NOW.minusSeconds(10)));
        var result = service.confirmVerifiedPayment(paid("TX002", 30000, NOW.minusSeconds(5)));
        assertEquals("ATTEMPT_REQUIRES_REVIEW", result.applicationReason());
        assertEquals("QUOTED", calculation.getStatus()); assertEquals(2, attempt.getReceipt().size());
    }

    @Test void lateCallbackUsesPaymentTimeNotReceiptTime() {
        calculation.setValidUntil(NOW.minusSeconds(5));
        var result = service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        assertEquals("APPLIED", result.applicationStatus());
    }

    @Test void actualLatePaymentRequiresReview() {
        calculation.setValidUntil(NOW.minusSeconds(20));
        var result = service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        assertEquals("QUOTE_EXPIRED", result.applicationReason());
    }

    @Test void supersededCalculationKeepsTransferForReview() {
        calculation.setStatus("SUPERSEDED");
        assertEquals("CALCULATION_SUPERSEDED", service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10))).applicationReason());
        assertEquals(1, attempt.getReceipt().size()); verifyNoInteractions(events);
    }

    @Test void changedLiveSessionScopeDoesNotDiscardStaleQuoteMoney() {
        calculation.setStatus("SUPERSEDED");
        VehicleEntity otherVehicle = new VehicleEntity(); otherVehicle.setId(99L); session.setVehicle(otherVehicle);
        ParkingLotEntity otherLot = new ParkingLotEntity(); otherLot.setId(99L); session.setParkingLot(otherLot);
        var result = service.confirmVerifiedPayment(paid("TX001", 30000, NOW.minusSeconds(10)));
        assertEquals("CALCULATION_SUPERSEDED", result.applicationReason()); assertEquals(1, attempt.getReceipt().size());
    }

    @Test void incorrectInvoiceCannotAlterMoneyOrCalculation() {
        var evidence = new VerifiedParkingPayment(44L, "SEPAY", "TX001", "WRONG", 30000L,
                "VND", NOW.minusSeconds(10), NOW, "PAID", Map.of());
        assertEquals("FEE_PAYMENT_IDENTITY_MISMATCH", assertThrows(BusinessException.class,
                () -> service.confirmVerifiedPayment(evidence)).getCode());
        assertTrue(observed.isEmpty()); assertEquals("PENDING", intent.getStatus());
    }

    @Test void failedOrPendingNotificationsCannotEraseReceivedMoney() {
        service.confirmVerifiedPayment(paid("TX001", 10000, NOW.minusSeconds(10)));
        for (String status : List.of("FAILED", "PENDING")) {
            service.confirmVerifiedPayment(new VerifiedParkingPayment(44L, "SEPAY", null, "INV001",
                    30000L, "VND", null, NOW, status, Map.of()));
        }
        assertEquals("REVIEW_REQUIRED", intent.getStatus()); assertEquals(1, attempt.getReceipt().size());
    }

    @Test void noChargeSettlementDoesNotCreateFakePaymentAndIsIdempotent() {
        calculation.setTotalVnd(0);
        assertEquals("NO_CHARGE", service.settleNoCharge(100L).status()); service.settleNoCharge(100L);
        verifyNoInteractions(intents, attempts, keys, payments);
        assertEquals("SETTLED", calculation.getStatus()); assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    }

    @Test void sameRequestKeyReturnsOriginalIntentWithoutAnotherGatewayCall() {
        when(attempts.findByRequestKey("request-A")).thenReturn(Optional.of(attempt));
        var result = service.createPaymentRequest(100L, "request-A");
        assertEquals(44L, result.paymentId()); verifyNoInteractions(payments);
    }

    @Test void sameRequestKeyBoundToDifferentCalculationIsRejected() {
        attempt.setCalculationId(999L); when(attempts.findByRequestKey("request-A")).thenReturn(Optional.of(attempt));
        assertEquals("FEE_REQUEST_KEY_CONFLICT", assertThrows(BusinessException.class,
                () -> service.createPaymentRequest(100L, "request-A")).getCode()); verifyNoInteractions(payments);
    }

    @Test void zeroQuoteCannotCreateGatewayIntent() {
        calculation.setTotalVnd(0);
        assertEquals("FEE_NO_CHARGE_REQUIRED", assertThrows(BusinessException.class,
                () -> service.createPaymentRequest(100L, "new-request")).getCode()); verifyNoInteractions(payments);
    }

    @Test void expiredQuoteCannotCreateGatewayIntent() {
        calculation.setValidUntil(NOW.minusSeconds(1));
        assertEquals("FEE_QUOTE_EXPIRED", assertThrows(BusinessException.class,
                () -> service.createPaymentRequest(100L, "new-request")).getCode()); verifyNoInteractions(payments);
    }

    @Test void newRequestUsesBackendPriceAndBindsSharedIntentToExactCalculation() {
        CreatePaymentResponse link = new CreatePaymentResponse(); link.setResultCd("00_000");
        link.setInvoiceNo("NEW001"); link.setAmount(BigDecimal.valueOf(30000)); link.setPaymentLink("https://example.invalid/qr");
        when(payments.createPaymentLink(any())).thenReturn(link);
        when(attempts.saveAndFlush(any())).thenAnswer(call -> {
            ParkingFeePaymentEntity saved = call.getArgument(0); if (saved.getId() == null) saved.setId(99L); return saved;
        });
        when(intents.saveAndFlush(any())).thenAnswer(call -> {
            PaymentEntity saved = call.getArgument(0); saved.setId(55L);
            assertEquals(100L, saved.getCallbackContext().getLong("calculationId"));
            assertEquals(99L, saved.getCallbackContext().getLong("parkingFeePaymentId"));
            assertEquals(1L, saved.getCallbackContext().getLong("sessionId"));
            assertEquals("SESSION", saved.getCallbackType()); return saved;
        });
        var result = service.createPaymentRequest(100L, "new-request");
        assertEquals(30000, result.amountVnd()); assertEquals(55L, result.paymentId()); assertEquals(99L, result.attemptId());
        verify(payments).createPaymentLink(argThat(request -> request.getAmount().compareTo(BigDecimal.valueOf(30000)) == 0));
    }

    @Test void reconciliationEvidenceReadsReturnCopiesWithoutGatewayOrMutation() {
        when(entityManager.find(FeeCalculationEntity.class, 100L)).thenReturn(calculation);
        service.confirmVerifiedPayment(paid("TX001", 10000, NOW.minusSeconds(10)));
        when(attempts.findByCalculationIdOrderByIdAsc(100L)).thenReturn(List.of(attempt));
        var result = service.getAttempts(100L).getFirst();
        ((com.fasterxml.jackson.databind.node.ArrayNode) result.observations()).removeAll();
        assertEquals(1, attempt.getReceipt().size()); verifyNoInteractions(payments);
    }

    @Test void newRequestKeyCannotBypassAnAttemptWaitingForReconciliation() {
        attempt.setStatus("REVIEW_REQUIRED"); attempt.setApplicationStatus("REVIEW_REQUIRED");
        when(attempts.findByCalculationIdOrderByIdAsc(100L)).thenReturn(List.of(attempt));
        var error = assertThrows(BusinessException.class, () -> service.createPaymentRequest(100L, "another-key"));
        assertEquals("FEE_ATTEMPT_REVIEW_REQUIRED", error.getCode()); verifyNoInteractions(payments);
    }

    @Test void replayingRequestUnderReviewDoesNotOfferAnotherPaymentLink() {
        attempt.setStatus("REVIEW_REQUIRED"); intent.setStatus("REVIEW_REQUIRED");
        when(attempts.findByRequestKey("request-A")).thenReturn(Optional.of(attempt));
        assertNull(service.createPaymentRequest(100L, "request-A").gatewayLink()); verifyNoInteractions(payments);
    }

    @Test void amountExceedingSharedPaymentPrecisionIsRejectedBeforeGatewayCall() {
        calculation.setTotalVnd(10_000_000_000_000_000L);
        var error = assertThrows(BusinessException.class, () -> service.createPaymentRequest(100L, "large-fee"));
        assertEquals("FEE_PAYMENT_AMOUNT_LIMIT", error.getCode()); verifyNoInteractions(payments);
    }
}
