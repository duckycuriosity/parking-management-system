package vio.barie.core.service;

import cn.hutool.json.JSONObject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.common.SubscriptionPeriodStatus;
import vio.barie.core.common.SubscriptionReceiptOutcome;
import vio.barie.core.common.SubscriptionRegistrationType;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.subscription.RegisterSubscriptionRequest;
import vio.barie.core.dto.subscription.RenewSubscriptionRequest;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.dto.subscription.SubscriptionReceiptResponse;
import vio.barie.core.dto.subscription.VerifiedSubscriptionPayment;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.entity.VehicleSubscriptionReceiptEntity;
import vio.barie.core.event.SubscriptionEntitlementsChanged;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.repository.VehicleSubscriptionReceiptRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.utils.VehicleType;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Contract-level unit tests. Row locks, exclusion constraints and rollback are
 * covered separately using PostgreSQL; Mockito cannot prove those guarantees.
 */
@ExtendWith(MockitoExtension.class)
class VehicleSubscriptionServiceTest {

    private static final Long VEHICLE = 12L;
    private static final Long LOT = 33L;
    private static final Long PACKAGE = 41L;
    private static final Long SUBSCRIPTION = 51L;
    private static final Long PAYMENT = 61L;
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");
    private static final Instant START = Instant.parse("2026-10-03T07:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T07:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(3_600);

    @Mock private VehicleSubscriptionRepository subscriptions;
    @Mock private SubscriptionPackageRepository packages;
    @Mock private VehicleSubscriptionReceiptRepository receipts;
    @Mock private PaymentRepository payments;
    @Mock private EntityManager entityManager;
    @Mock private ApplicationEventPublisher events;

    private VehicleSubscriptionService service;
    private SubscriptionPackageEntity catalog;
    private VehicleSubscriptionEntity pending;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        service = new VehicleSubscriptionService(subscriptions, packages, receipts, payments,
                Clock.fixed(NOW, ZoneOffset.UTC), entityManager, events);
        catalog = catalog();
        pending = subscription(SUBSCRIPTION, START, END, VehicleSubscriptionStatus.PENDING_PAYMENT);
        payment = payment();
        lenient().when(subscriptions.lockVehicle(anyLong()))
                .thenAnswer(call -> Optional.of(call.<Long>getArgument(0)));
        lenient().when(subscriptions.lockSubscription(anyLong()))
                .thenAnswer(call -> Optional.of(call.<Long>getArgument(0)));
        lenient().when(subscriptions.findVehicleType(VEHICLE)).thenReturn(Optional.of("CAR"));
        lenient().when(subscriptions.parkingLotExists(anyLong())).thenReturn(true);
        lenient().when(packages.parkingLotExists(anyLong())).thenReturn(true);
        lenient().when(packages.findById(PACKAGE)).thenReturn(Optional.of(catalog));
        lenient().when(packages.lockPackage(PACKAGE)).thenReturn(Optional.of(catalog));
        lenient().when(subscriptions.findById(SUBSCRIPTION)).thenReturn(Optional.of(pending));
        lenient().when(subscriptions.saveAndFlush(any())).thenAnswer(call -> persistSubscription(call.getArgument(0)));
        lenient().when(subscriptions.save(any())).thenAnswer(call -> persistSubscription(call.getArgument(0)));
        lenient().when(receipts.saveAndFlush(any())).thenAnswer(call -> persistReceipt(call.getArgument(0)));
        lenient().when(subscriptions.existsPaidOverlapping(anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> !subscriptions.findPaidOverlapping(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)).isEmpty());
        lenient().when(subscriptions.findPaidOverlappingWithPackageSchedules(anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> subscriptions.findPaidOverlapping(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)));
        lenient().when(payments.findById(PAYMENT)).thenReturn(Optional.of(payment));
        lenient().when(payments.lockById(PAYMENT)).thenReturn(Optional.of(payment));
    }

    @Test
    void registrationCreatesOnlyPendingQuoteWithExactlyThirtyTimesTwentyFourHours() {
        SubscriptionResponse response = service.register(registerRequest());

        assertEquals(SUBSCRIPTION, response.getId());
        assertEquals(START, response.getStartsAt());
        assertEquals(END, response.getEndsAt());
        assertEquals(30L * 86_400L, response.getEndsAt().getEpochSecond() - response.getStartsAt().getEpochSecond());
        assertEquals(5_000L, response.getPriceVnd());
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, response.getStatus());
        assertEquals(SubscriptionPeriodStatus.NONE, response.getPeriodStatus());
        assertEquals(SubscriptionRegistrationType.NEW, response.getRegistrationType());
        assertNull(response.getPaidAt());
        verifyNoInteractions(payments, receipts);
    }

    @Test
    void parentVehicleIsLockedBeforeRegistrationOverlapCheck() {
        service.register(registerRequest());

        InOrder order = inOrder(subscriptions);
        order.verify(subscriptions).lockVehicle(VEHICLE);
        order.verify(subscriptions).findPaidOverlapping(VEHICLE, LOT, START, END);
    }

    @Test
    void repeatedIdenticalRequestReturnsOriginalQuoteEvenAfterSaleCloses() {
        catalog.setSaleEnabled(false);
        when(subscriptions.findByRequestKey("request-1")).thenReturn(Optional.of(pending));

        SubscriptionResponse response = service.register(registerRequest());

        assertEquals(SUBSCRIPTION, response.getId());
        assertEquals(5_000L, response.getPriceVnd());
        verify(subscriptions, never()).save(any());
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void requestKeyCannotBeReusedForDifferentTerms() {
        when(subscriptions.findByRequestKey("request-1")).thenReturn(Optional.of(pending));
        RegisterSubscriptionRequest request = registerRequest();
        request.setStartsAt(START.plusSeconds(1));

        assertThrows(BusinessException.class, () -> service.register(request));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void missingVehicleIsRejectedBeforeAnyQuoteIsWritten() {
        when(subscriptions.lockVehicle(VEHICLE)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.register(registerRequest()));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void missingParkingLotIsRejectedBeforeAnyQuoteIsWritten() {
        lenient().when(subscriptions.parkingLotExists(LOT)).thenReturn(false);
        lenient().when(packages.parkingLotExists(LOT)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.register(registerRequest()));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void missingPackageIsRejected() {
        when(packages.findById(PACKAGE)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.register(registerRequest()));
    }

    @ParameterizedTest(name = "unavailable catalog version {index}")
    @MethodSource("unavailableCatalogChanges")
    void invalidCatalogCannotProducePurchase(Consumer<SubscriptionPackageEntity> change) {
        change.accept(catalog);

        assertThrows(BusinessException.class, () -> service.register(registerRequest()));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    static Stream<Consumer<SubscriptionPackageEntity>> unavailableCatalogChanges() {
        return Stream.of(
                p -> p.setParkingLotId(LOT + 1),
                p -> p.setVehicleType(VehicleType.MOTORBIKE),
                p -> p.setSaleEnabled(false),
                p -> p.setStatus(SubscriptionPackageStatus.DRAFT),
                p -> p.setEffectiveFrom(NOW.plusSeconds(1)),
                p -> p.setEffectiveTo(NOW),
                p -> p.setPriceVnd(0L),
                p -> p.setPriceVnd(-1L),
                p -> p.setDurationDays(0)
        );
    }

    @ParameterizedTest(name = "invalid explicit request {index}")
    @MethodSource("invalidRegistrationChanges")
    void invalidExplicitRegistrationIsRejected(Consumer<RegisterSubscriptionRequest> change) {
        RegisterSubscriptionRequest request = registerRequest();
        change.accept(request);

        assertThrows(BusinessException.class, () -> service.register(request));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    static Stream<Consumer<RegisterSubscriptionRequest>> invalidRegistrationChanges() {
        return Stream.of(
                r -> r.setVehicleId(null),
                r -> r.setVehicleId(0L),
                r -> r.setParkingLotId(null),
                r -> r.setPackageId(null),
                r -> r.setStartsAt(null),
                r -> r.setRequestKey("  "),
                r -> r.setRequestKey("a".repeat(101)),
                r -> r.setPaymentDeadlineAt(null),
                r -> r.setPaymentDeadlineAt(NOW)
        );
    }

    @Test
    void paidOverlapPreventsRegistrationWhileAdjacentPeriodDoesNot() {
        VehicleSubscriptionEntity prior = subscription(50L, START.minusSeconds(86_400), START,
                VehicleSubscriptionStatus.PAID);
        when(subscriptions.findPaidOverlapping(VEHICLE, LOT, START, END)).thenReturn(List.of(prior));

        assertThrows(BusinessException.class, () -> service.register(registerRequest()));
        when(subscriptions.findPaidOverlapping(VEHICLE, LOT, START, END)).thenReturn(List.of());

        assertEquals(START, service.register(registerRequest()).getStartsAt());
    }

    @Test
    void renewalCreatesFuturePeriodWithoutChangingPreviousPeriod() {
        VehicleSubscriptionEntity previous = paidPrevious(NOW.plusSeconds(86_400));
        Instant previousStart = previous.getStartsAt();
        Instant previousEnd = previous.getEndsAt();
        when(subscriptions.findById(50L)).thenReturn(Optional.of(previous));

        SubscriptionResponse response = service.renew(renewRequest());

        assertEquals(previousEnd, response.getStartsAt());
        assertEquals(previousEnd.plusSeconds(30L * 86_400), response.getEndsAt());
        assertEquals(50L, response.getPreviousSubscriptionId());
        assertEquals(SubscriptionRegistrationType.RENEW, response.getRegistrationType());
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, response.getStatus());
        assertEquals(previousStart, previous.getStartsAt());
        assertEquals(previousEnd, previous.getEndsAt());
        assertEquals(VehicleSubscriptionStatus.PAID, previous.getStatus());
    }

    @Test
    void lateRenewalStillStartsAtPreviousEndRatherThanCurrentTime() {
        VehicleSubscriptionEntity previous = paidPrevious(NOW.minusSeconds(5L * 86_400));
        when(subscriptions.findById(50L)).thenReturn(Optional.of(previous));

        SubscriptionResponse response = service.renew(renewRequest());

        assertEquals(previous.getEndsAt(), response.getStartsAt());
        assertEquals(NOW.plusSeconds(25L * 86_400), response.getEndsAt());
        assertTrue(response.getStartsAt().isBefore(NOW));
    }

    @Test
    void pendingPeriodCannotBeRenewed() {
        VehicleSubscriptionEntity previous = paidPrevious(NOW);
        previous.setStatus(VehicleSubscriptionStatus.PENDING_PAYMENT);
        when(subscriptions.findById(50L)).thenReturn(Optional.of(previous));

        assertThrows(BusinessException.class, () -> service.renew(renewRequest()));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void renewalMustTargetLastPaidPeriodOfItsChain() {
        VehicleSubscriptionEntity previous = paidPrevious(NOW);
        VehicleSubscriptionEntity successor = subscription(52L, NOW, NOW.plusSeconds(30L * 86_400),
                VehicleSubscriptionStatus.PAID);
        successor.setPreviousSubscriptionId(50L);
        successor.setRegistrationType(SubscriptionRegistrationType.RENEW);
        when(subscriptions.findById(50L)).thenReturn(Optional.of(previous));
        when(subscriptions.findPaidOverlapping(VEHICLE, LOT, NOW, NOW.plusSeconds(30L * 86_400)))
                .thenReturn(List.of(successor));

        assertThrows(BusinessException.class, () -> service.renew(renewRequest()));
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void successfulVerifiedReceiptActivatesQuotedPriceWithoutFeeRounding() {
        pending.setPriceVnd(5_123L);
        payment.setAmount(BigDecimal.valueOf(5_123L));

        SubscriptionResponse response = service.confirmVerifiedPayment(evidence(5_123L, "VND", NOW, NOW));

        assertEquals(VehicleSubscriptionStatus.PAID, response.getStatus());
        assertEquals(SubscriptionPeriodStatus.IN_TERM, response.getPeriodStatus());
        assertEquals(5_123L, response.getPriceVnd());
        assertEquals(NOW, pending.getPaidAt());
        assertEquals(71L, pending.getPaidReceiptId());
        assertEquals(71L, response.getPaidReceiptId());
        assertEquals("PAID", payment.getStatus());
        assertEquals(START, pending.getStartsAt());
        assertEquals(END, pending.getEndsAt());
        assertEquals(SubscriptionReceiptOutcome.APPLIED, savedReceipt().getOutcome());
        ArgumentCaptor<SubscriptionEntitlementsChanged> change = ArgumentCaptor.forClass(SubscriptionEntitlementsChanged.class);
        InOrder order = inOrder(subscriptions, events);
        order.verify(subscriptions).saveAndFlush(pending);
        order.verify(events).publishEvent(change.capture());
        assertEquals(SUBSCRIPTION, change.getValue().subscriptionId());
        assertEquals(VEHICLE, change.getValue().vehicleId());
        assertEquals(LOT, change.getValue().parkingLotId());
        assertEquals(START, change.getValue().startsAt());
        assertEquals(END, change.getValue().endsAt());
        assertEquals(pending.getPaidReceiptId(), change.getValue().paidReceiptId());
    }

    @Test
    void settlementLocksVehicleThenSubscriptionThenPaymentBeforeGranting() {
        service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW));

        InOrder order = inOrder(subscriptions, payments);
        order.verify(subscriptions).lockVehicle(VEHICLE);
        order.verify(subscriptions).lockSubscription(SUBSCRIPTION);
        order.verify(payments).lockById(PAYMENT);
        verify(entityManager).refresh(pending);
        verify(entityManager).refresh(payment);
    }

    @Test
    void missingReceiptObservationTimeUsesServerClockWithoutGuessingUnknownPaidTime() {
        service.confirmVerifiedPayment(evidence(5_000L, "VND", null, null));

        VehicleSubscriptionReceiptEntity receipt = savedReceipt();
        assertEquals(NOW, receipt.getReceivedAt());
        assertNull(receipt.getPaidAt());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, receipt.getOutcome());
        assertEquals("PAYMENT_TIME_UNKNOWN", receipt.getReasonCode());
        assertNotEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals("REVIEW_REQUIRED", payment.getStatus());
    }

    @Test
    void stoppingPackageSalesDoesNotAlterPreviouslyQuotedPriceOrRights() {
        catalog.setSaleEnabled(false);
        catalog.setEffectiveTo(NOW);

        SubscriptionResponse response = service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW));

        assertEquals(VehicleSubscriptionStatus.PAID, response.getStatus());
        assertEquals(PACKAGE, response.getPackageId());
        assertEquals(1, response.getPackageVersionNo());
        assertEquals(5_000L, response.getPriceVnd());
    }

    @Test
    void delayedCallbackUsesVerifiedPaidTimeRatherThanReceiptTimeForDeadline() {
        pending.setPaymentDeadlineAt(NOW.minusSeconds(60));

        SubscriptionResponse response = service.confirmVerifiedPayment(
                evidence(5_000L, "VND", NOW.minusSeconds(120), NOW));

        assertEquals(VehicleSubscriptionStatus.PAID, response.getStatus());
        assertEquals(NOW.minusSeconds(120), response.getPaidAt());
    }

    @ParameterizedTest(name = "verified receipt needing reconciliation {index}")
    @MethodSource("reviewEvidence")
    void exceptionReceiptsRemainAuditableWithoutGranting(VerifiedSubscriptionPayment evidence) {
        service.confirmVerifiedPayment(evidence);

        assertNotEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertNull(pending.getPaidAt());
        assertNull(pending.getPaidReceiptId());
        VehicleSubscriptionReceiptEntity receipt = savedReceipt();
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, receipt.getOutcome());
        assertEquals(evidence.amountVnd(), receipt.getAmountVnd());
        assertEquals(evidence.paidAt(), receipt.getPaidAt());
        assertNotNull(receipt.getReasonCode());
        // Legacy revenue reports sum PAID intents using the quoted amount.
        // A mismatched or unqualified transfer must be accounted for via its
        // receipt and must not make those reports count the quote as revenue.
        assertEquals("REVIEW_REQUIRED", payment.getStatus());
        assertEquals(BigDecimal.valueOf(5_000L), payment.getAmount());
        verifyNoInteractions(events);
    }

    @Test
    void nanosecondPaymentTimestampReplaysAgainstPersistedMicrosecondReceiptWithoutDuplicateGrant() {
        Instant paidTime = NOW.minusSeconds(1).plusNanos(123_456_789);
        Instant receivedTime = NOW.plusNanos(123_456_789);
        Instant storedPaidTime = Instant.parse("2026-10-09T01:59:59.123456Z");
        Instant storedReceivedTime = Instant.parse("2026-10-09T02:00:00.123456Z");
        service = new VehicleSubscriptionService(subscriptions, packages, receipts, payments,
                Clock.fixed(NOW.plusNanos(500_000_789), ZoneOffset.UTC), entityManager, events);
        VerifiedSubscriptionPayment original = evidence(5_000L, "VND", paidTime, receivedTime);

        SubscriptionResponse granted = service.confirmVerifiedPayment(original);
        VehicleSubscriptionReceiptEntity persisted = savedReceipt();
        when(receipts.findBySourceAndReference("SEPAY", "tx-1")).thenReturn(Optional.of(persisted));
        SubscriptionResponse replay = service.confirmVerifiedPayment(original);

        assertEquals(VehicleSubscriptionStatus.PAID, granted.getStatus());
        assertEquals(storedPaidTime, granted.getPaidAt());
        assertEquals(storedPaidTime, persisted.getPaidAt());
        assertEquals(storedReceivedTime, persisted.getReceivedAt());
        assertEquals(granted.getPaidAt(), replay.getPaidAt());
        assertEquals(END, replay.getEndsAt());
        verify(receipts, times(1)).saveAndFlush(any());
        verify(subscriptions, times(1)).saveAndFlush(any());
        verify(events, times(1)).publishEvent(any(SubscriptionEntitlementsChanged.class));
    }

    @Test
    void automaticallyObservedReceiptTimeIsNormalizedToDatabasePrecision() {
        service = new VehicleSubscriptionService(subscriptions, packages, receipts, payments,
                Clock.fixed(NOW.plusNanos(987_654_321), ZoneOffset.UTC), entityManager, events);

        service.confirmVerifiedPayment(evidence(5_000L, "VND", null, null));

        assertEquals(Instant.parse("2026-10-09T02:00:00.987654Z"), savedReceipt().getReceivedAt());
        assertNull(savedReceipt().getPaidAt());
        assertEquals("REVIEW_REQUIRED", payment.getStatus());
    }

    @Test
    void maximumSePayReferenceFitsExistingPaymentTransactionColumn() {
        String reference = "1".repeat(50);
        VerifiedSubscriptionPayment v = new VerifiedSubscriptionPayment(SUBSCRIPTION, PAYMENT,
                "SEPAY", reference, "SUB-61", 5_000L, "VND", NOW, NOW, Map.of());

        service.confirmVerifiedPayment(v);

        assertEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals(reference, payment.getSepayTxId());
        assertEquals(reference, savedReceipt().getReference());
    }

    @Test
    void unsupportedSePayReferenceIsRejectedBeforeSettlementWrites() {
        VerifiedSubscriptionPayment v = new VerifiedSubscriptionPayment(SUBSCRIPTION, PAYMENT,
                "SEPAY", "1".repeat(51), "SUB-61", 5_000L, "VND", NOW, NOW, Map.of());

        assertThrows(BusinessException.class, () -> service.confirmVerifiedPayment(v));

        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, pending.getStatus());
        assertEquals("PENDING", payment.getStatus());
        verify(receipts, never()).saveAndFlush(any());
        verify(subscriptions, never()).saveAndFlush(any());
    }

    static Stream<VerifiedSubscriptionPayment> reviewEvidence() {
        return Stream.of(
                evidence(4_999L, "VND", NOW, NOW),
                evidence(5_001L, "VND", NOW, NOW),
                evidence(5_000L, "USD", NOW, NOW),
                evidence(5_000L, "VND", null, NOW)
        );
    }

    @Test
    void paymentMadeAfterExplicitDeadlineIsAuditedWithoutGranting() {
        pending.setPaymentDeadlineAt(NOW.minusSeconds(60));

        service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW.minusSeconds(1), NOW));

        assertNotEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, savedReceipt().getOutcome());
        assertEquals("PAID_AFTER_DEADLINE", savedReceipt().getReasonCode());
    }

    @Test
    void alreadyExpiredPeriodRequiresReviewEvenIfPaymentWasOnTime() {
        pending.setStartsAt(NOW.minusSeconds(31L * 86_400));
        pending.setEndsAt(NOW.minusSeconds(86_400));

        service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW.minusSeconds(2L * 86_400), NOW));

        assertNotEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, savedReceipt().getOutcome());
    }

    @Test
    void paidOverlapAtSettlementIsRetainedForReviewInsteadOfGranting() {
        when(subscriptions.findPaidOverlapping(VEHICLE, LOT, START, END)).thenReturn(List.of(
                subscription(52L, START, END, VehicleSubscriptionStatus.PAID)));

        service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW));

        assertNotEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, savedReceipt().getOutcome());
    }

    @Test
    void replayOfAppliedTransactionDoesNotWriteAnotherReceiptOrExtendPeriod() {
        VehicleSubscriptionReceiptEntity receipt = existingReceipt(SubscriptionReceiptOutcome.APPLIED);
        pending.setStatus(VehicleSubscriptionStatus.PAID);
        pending.setPaidAt(NOW);
        pending.setPaidReceiptId(receipt.getId());
        payment.setStatus("PAID");
        when(receipts.findBySourceAndReference("SEPAY", "tx-1")).thenReturn(Optional.of(receipt));

        SubscriptionResponse response = service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW));

        assertEquals(VehicleSubscriptionStatus.PAID, response.getStatus());
        assertEquals(END, response.getEndsAt());
        verify(receipts, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void sameTransactionCannotActivateDifferentSubscription() {
        VehicleSubscriptionReceiptEntity receipt = existingReceipt(SubscriptionReceiptOutcome.APPLIED);
        receipt.setSubscriptionId(52L);
        when(receipts.findBySourceAndReference("SEPAY", "tx-1")).thenReturn(Optional.of(receipt));

        assertThrows(BusinessException.class,
                () -> service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW)));
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, pending.getStatus());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void secondPhysicalTransferIsPreservedWithoutChangingAlreadyPaidPeriod() {
        pending.setStatus(VehicleSubscriptionStatus.PAID);
        pending.setPaidAt(NOW.minusSeconds(1));
        pending.setPaidReceiptId(70L);
        payment.setStatus("PAID");

        service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW));

        assertEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals(70L, pending.getPaidReceiptId());
        assertEquals(NOW.minusSeconds(1), pending.getPaidAt());
        assertEquals(END, pending.getEndsAt());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, savedReceipt().getOutcome());
    }

    @Test
    void callbackMustMatchPaymentInvoiceAndSubscriptionContext() {
        payment.setCallbackContext(new JSONObject(Map.of("subscriptionId", 999L)));

        assertThrows(BusinessException.class,
                () -> service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW)));
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, pending.getStatus());
    }

    @Test
    void cancelledQuoteWithActualMoneyIsRetainedForReconciliation() {
        pending.setStatus(VehicleSubscriptionStatus.CANCELLED);

        service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW));

        assertNotEquals(VehicleSubscriptionStatus.PAID, pending.getStatus());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, savedReceipt().getOutcome());
    }

    @Test
    void paymentLinkPreparationUsesFrozenQuoteAndCannotActivateIt() {
        catalog.setSaleEnabled(false);

        SubscriptionResponse response = service.preparePaymentLink(SUBSCRIPTION);

        assertEquals(5_000L, response.getPriceVnd());
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, response.getStatus());
        verifyNoInteractions(receipts);
        verify(subscriptions, never()).saveAndFlush(any());
    }

    @Test
    void expiredPaymentDeadlineCannotCreateFreshLink() {
        pending.setPaymentDeadlineAt(NOW);

        assertThrows(BusinessException.class, () -> service.preparePaymentLink(SUBSCRIPTION));
        verifyNoInteractions(receipts);
    }

    @Test
    void currentQueryUsesInjectedClockAndReturnsOnlyPaidPeriods() {
        VehicleSubscriptionEntity paid = subscription(SUBSCRIPTION, START, END, VehicleSubscriptionStatus.PAID);
        when(subscriptions.findPaidAt(VEHICLE, LOT, NOW)).thenReturn(List.of(paid));

        List<SubscriptionResponse> result = service.current(VEHICLE, LOT);

        assertEquals(1, result.size());
        assertEquals(SubscriptionPeriodStatus.IN_TERM, result.get(0).getPeriodStatus());
        verify(subscriptions).findPaidAt(VEHICLE, LOT, NOW);
    }

    @Test
    void periodStartIsInclusiveAndEndIsExclusiveWhenRenderingHistory() {
        VehicleSubscriptionEntity upcoming = subscription(1L, NOW.plusSeconds(1), END, VehicleSubscriptionStatus.PAID);
        VehicleSubscriptionEntity starting = subscription(2L, NOW, END, VehicleSubscriptionStatus.PAID);
        VehicleSubscriptionEntity expired = subscription(3L, START, NOW, VehicleSubscriptionStatus.PAID);
        when(subscriptions.findByVehicleIdAndParkingLotIdOrderByStartsAtAscIdAsc(VEHICLE, LOT))
                .thenReturn(List.of(upcoming, starting, expired, pending));

        List<SubscriptionResponse> result = service.history(VEHICLE, LOT);

        assertEquals(List.of(SubscriptionPeriodStatus.UPCOMING, SubscriptionPeriodStatus.IN_TERM,
                SubscriptionPeriodStatus.EXPIRED, SubscriptionPeriodStatus.NONE),
                result.stream().map(SubscriptionResponse::getPeriodStatus).toList());
    }

    @Test
    void invalidCoverageLookupIntervalIsRejectedBeforeQuerying() {
        assertThrows(BusinessException.class, () -> service.findPaidOverlapping(VEHICLE, LOT, NOW, NOW));
        assertThrows(BusinessException.class,
                () -> service.findPaidOverlapping(VEHICLE, LOT, NOW, NOW.minusSeconds(1)));
        verify(subscriptions, never()).findPaidOverlapping(anyLong(), anyLong(), any(), any());
    }

    @Test
    void receiptHistoryDistinguishesGrantedMoneyFromExtraTransfersNeedingReview() {
        VehicleSubscriptionReceiptEntity granted = existingReceipt(SubscriptionReceiptOutcome.APPLIED);
        VehicleSubscriptionReceiptEntity extra = existingReceipt(SubscriptionReceiptOutcome.REVIEW_REQUIRED);
        extra.setId(72L);
        extra.setReference("tx-2");
        extra.setAmountVnd(4_000L);
        extra.setReasonCode("SECOND_SETTLEMENT");
        extra.setPaidAt(null);
        extra.setMetadata(Map.of("providerSecret", "must-not-be-returned"));
        when(receipts.findBySubscriptionIdOrderByReceivedAtAscIdAsc(SUBSCRIPTION))
                .thenReturn(List.of(granted, extra));

        List<SubscriptionReceiptResponse> result = service.paymentReceipts(SUBSCRIPTION);

        assertEquals(2, result.size());
        assertEquals(SubscriptionReceiptOutcome.APPLIED, result.get(0).outcome());
        assertEquals(4_000L, result.get(1).amountVnd());
        assertEquals(SubscriptionReceiptOutcome.REVIEW_REQUIRED, result.get(1).outcome());
        assertEquals("SECOND_SETTLEMENT", result.get(1).reasonCode());
        assertNull(result.get(1).paidAt());
        assertFalse(java.util.Arrays.stream(SubscriptionReceiptResponse.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("metadata")));
        verifyNoInteractions(payments, events);
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void missingSubscriptionIsNotReportedAsAnEmptyReceiptHistory() {
        when(subscriptions.findById(SUBSCRIPTION)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.paymentReceipts(SUBSCRIPTION));
        verifyNoInteractions(receipts);
    }

    @Test
    void invalidSubscriptionIdentifierCannotReadReceiptHistory() {
        assertThrows(BusinessException.class, () -> service.paymentReceipts(0L));
        verifyNoInteractions(receipts, subscriptions);
    }

    @Test
    void settlementHookRejectsReadOnlyTransactionsBeforeTakingLocks() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThrows(BusinessException.class,
                    () -> service.lockPaidPeriodsForSettlement(VEHICLE, LOT, START, END));
            verifyNoInteractions(subscriptions, entityManager);
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
    }

    @Test
    void settlementHookLocksVehicleAndRefreshesCachedRightsBeforeReturningQuoteInputs() {
        VehicleSubscriptionEntity cached = subscription(77L, START, END, VehicleSubscriptionStatus.PENDING_PAYMENT);
        when(subscriptions.findPaidOverlapping(VEHICLE, LOT, START, END)).thenReturn(List.of(cached));
        doAnswer(call -> {
            cached.setStatus(VehicleSubscriptionStatus.PAID);
            cached.setPaidAt(NOW.minusSeconds(1));
            return null;
        }).when(entityManager).refresh(cached);

        List<SubscriptionResponse> fresh = service.lockPaidPeriodsForSettlement(VEHICLE, LOT, START, END);

        assertEquals(1, fresh.size());
        assertEquals(VehicleSubscriptionStatus.PAID, fresh.get(0).getStatus());
        assertEquals(SubscriptionPeriodStatus.IN_TERM, fresh.get(0).getPeriodStatus());
        InOrder order = inOrder(subscriptions, entityManager);
        order.verify(subscriptions).lockVehicle(VEHICLE);
        order.verify(subscriptions).findPaidOverlapping(VEHICLE, LOT, START, END);
        order.verify(entityManager).refresh(cached);
        verifyNoInteractions(payments, receipts, events);
    }

    @Test
    void settlementHookCannotReleaseItsVehicleLockInAnIndependentTransaction() {
        // Exercise Spring's actual transaction advice, rather than invoking the
        // service directly and assuming that its transaction contract is active.
        AbstractPlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) {
                throw new AssertionError("The hook must join checkout's transaction, never create its own");
            }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        VehicleSubscriptionService advised = (VehicleSubscriptionService) proxyFactory.getProxy();

        assertThrows(IllegalTransactionStateException.class,
                () -> advised.lockPaidPeriodsForSettlement(VEHICLE, LOT, START, END));
        verify(subscriptions, never()).lockVehicle(anyLong());
    }

    @Test
    void weeklyScheduleIsReturnedWithItsOriginalVersionAndNoHardcodedOfficeHours() {
        catalog.setCoverageMode(SubscriptionCoverageMode.WEEKLY);
        SubscriptionPackageScheduleEntity schedule = new SubscriptionPackageScheduleEntity();
        schedule.setId(81L);
        schedule.setWeekday(1);
        schedule.setStartTime(LocalTime.of(22, 0));
        schedule.setEndTime(LocalTime.of(6, 0));
        catalog.addSchedule(schedule);

        SubscriptionResponse response = service.getById(SUBSCRIPTION);

        assertEquals(SubscriptionCoverageMode.WEEKLY, response.getCoverageMode());
        assertEquals(1, response.getPackageVersionNo());
        assertEquals(1, response.getSchedules().size());
        assertEquals(1, response.getSchedules().get(0).getWeekday());
        assertEquals(LocalTime.of(22, 0), response.getSchedules().get(0).getStartTime());
        assertEquals(LocalTime.of(6, 0), response.getSchedules().get(0).getEndTime());
    }

    @Test
    void concurrentOrderRequestKeyDuplicateIs409WithoutQueryingTheFailedTransaction() {
        DataIntegrityViolationException duplicate = databaseViolation("uq_vehicle_subscription_request_key", "23505");
        doThrow(duplicate).when(subscriptions).saveAndFlush(any());

        BusinessException failure = assertThrows(BusinessException.class, () -> service.register(registerRequest()));

        assertEquals(409, failure.getHttpStatus());
        assertEquals("SUBSCRIPTION_CONFLICT", failure.getCode());
        verify(subscriptions, times(1)).findByRequestKey("request-1");
        verifyNoInteractions(receipts, payments, events);
    }

    @Test
    void concurrentPhysicalReferenceDuplicateIs409WithoutGrantingOrRetryingTheFailedTransaction() {
        DataIntegrityViolationException duplicate = databaseViolation("uq_subscription_receipt_source_reference", "23505");
        doThrow(duplicate).when(receipts).saveAndFlush(any());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW)));

        assertEquals(409, failure.getHttpStatus());
        assertEquals("SUBSCRIPTION_PAYMENT_REPLAY_CONFLICT", failure.getCode());
        verify(receipts, times(1)).findBySourceAndReference("SEPAY", "tx-1");
        verify(subscriptions, never()).saveAndFlush(any());
        verify(payments, never()).save(any());
        verifyNoInteractions(events);
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, pending.getStatus());
    }

    @ParameterizedTest
    @MethodSource("unrelatedDatabaseFailures")
    void orderForeignKeyAndTechnicalFailuresAreNotDisguisedAsRequestKeyConflicts(
            DataIntegrityViolationException technicalFailure) {
        doThrow(technicalFailure).when(subscriptions).saveAndFlush(any());

        DataIntegrityViolationException actual = assertThrows(DataIntegrityViolationException.class,
                () -> service.register(registerRequest()));

        assertSame(technicalFailure, actual);
        verify(subscriptions, times(1)).findByRequestKey("request-1");
        verifyNoInteractions(receipts, payments, events);
    }

    @ParameterizedTest
    @MethodSource("unrelatedDatabaseFailures")
    void receiptForeignKeyAndTechnicalFailuresAreNotDisguisedAsDuplicateTransfers(
            DataIntegrityViolationException technicalFailure) {
        doThrow(technicalFailure).when(receipts).saveAndFlush(any());

        DataIntegrityViolationException actual = assertThrows(DataIntegrityViolationException.class,
                () -> service.confirmVerifiedPayment(evidence(5_000L, "VND", NOW, NOW)));

        assertSame(technicalFailure, actual);
        verify(receipts, times(1)).findBySourceAndReference("SEPAY", "tx-1");
        verify(subscriptions, never()).saveAndFlush(any());
        verify(payments, never()).save(any());
        verifyNoInteractions(events);
        assertEquals(VehicleSubscriptionStatus.PENDING_PAYMENT, pending.getStatus());
    }

    private static Stream<DataIntegrityViolationException> unrelatedDatabaseFailures() {
        return Stream.of(databaseViolation("fk_subscription_payment", "23503"),
                databaseViolation("another_unique_constraint", "23505"),
                databaseViolation(null, "23502"),
                new DataIntegrityViolationException("Persistence failed", new IllegalStateException("technical failure")));
    }

    private static DataIntegrityViolationException databaseViolation(String constraint, String sqlState) {
        ConstraintViolationException violation = new ConstraintViolationException("Database rejected write",
                new SQLException("database error", sqlState), constraint);
        // Providers/Spring may wrap the Hibernate exception more than once.
        return new DataIntegrityViolationException("Could not flush", new IllegalStateException("provider wrapper", violation));
    }

    private VehicleSubscriptionReceiptEntity savedReceipt() {
        return mockingDetails(receipts).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("saveAndFlush") || call.getMethod().getName().equals("save"))
                .map(call -> (VehicleSubscriptionReceiptEntity) call.getArgument(0))
                .findFirst().orElseThrow(() -> new AssertionError("Verified money must have a persisted receipt"));
    }

    private static VehicleSubscriptionEntity persistSubscription(VehicleSubscriptionEntity entity) {
        if (entity.getId() == null) entity.setId(SUBSCRIPTION);
        return entity;
    }

    private static VehicleSubscriptionReceiptEntity persistReceipt(VehicleSubscriptionReceiptEntity entity) {
        if (entity.getId() == null) entity.setId(71L);
        return entity;
    }

    private static RegisterSubscriptionRequest registerRequest() {
        RegisterSubscriptionRequest request = new RegisterSubscriptionRequest();
        request.setVehicleId(VEHICLE);
        request.setParkingLotId(LOT);
        request.setPackageId(PACKAGE);
        request.setRequestKey("request-1");
        request.setStartsAt(START);
        request.setPaymentDeadlineAt(DEADLINE);
        return request;
    }

    private static RenewSubscriptionRequest renewRequest() {
        RenewSubscriptionRequest request = new RenewSubscriptionRequest();
        request.setPreviousSubscriptionId(50L);
        request.setPackageId(PACKAGE);
        request.setRequestKey("renew-1");
        request.setPaymentDeadlineAt(DEADLINE);
        return request;
    }

    private SubscriptionPackageEntity catalog() {
        SubscriptionPackageEntity p = new SubscriptionPackageEntity();
        p.setId(PACKAGE);
        p.setPackageCode("CAR-LOT-33-30D");
        p.setName("30 ngày");
        p.setVersionNo(1);
        p.setParkingLotId(LOT);
        p.setVehicleType(VehicleType.CAR);
        p.setPriceVnd(5_000L);
        p.setDurationDays(30);
        p.setCoverageMode(SubscriptionCoverageMode.ALL_DAY);
        p.setEffectiveFrom(NOW.minusSeconds(86_400));
        p.setStatus(SubscriptionPackageStatus.PUBLISHED);
        p.setSaleEnabled(true);
        return p;
    }

    private VehicleSubscriptionEntity subscription(Long id, Instant from, Instant until,
                                                    VehicleSubscriptionStatus status) {
        VehicleSubscriptionEntity entity = new VehicleSubscriptionEntity();
        entity.setId(id);
        entity.setRequestKey("request-1");
        entity.setVehicleId(VEHICLE);
        entity.setParkingLotId(LOT);
        entity.setSubscriptionPackage(catalog);
        entity.setRegistrationType(SubscriptionRegistrationType.NEW);
        entity.setPriceVnd(5_000L);
        entity.setStartsAt(from);
        entity.setEndsAt(until);
        entity.setPaymentDeadlineAt(DEADLINE);
        entity.setStatus(status);
        return entity;
    }

    private VehicleSubscriptionEntity paidPrevious(Instant endsAt) {
        return subscription(50L, endsAt.minusSeconds(30L * 86_400), endsAt, VehicleSubscriptionStatus.PAID);
    }

    private static PaymentEntity payment() {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(PAYMENT);
        entity.setInvoiceNo("SUB-61");
        entity.setAmount(BigDecimal.valueOf(5_000));
        entity.setCurrency("VND");
        entity.setStatus("PENDING");
        entity.setCallbackType("SUBSCRIPTION");
        entity.setCallbackContext(new JSONObject(Map.of("subscriptionId", SUBSCRIPTION)));
        entity.setGateway("SEPAY");
        return entity;
    }

    private static VerifiedSubscriptionPayment evidence(Long amount, String currency,
                                                        Instant paidAt, Instant receivedAt) {
        return new VerifiedSubscriptionPayment(SUBSCRIPTION, PAYMENT, "SEPAY", "tx-1", "SUB-61",
                amount, currency, paidAt, receivedAt, Map.of("verified_by", "gateway"));
    }

    private static VehicleSubscriptionReceiptEntity existingReceipt(SubscriptionReceiptOutcome outcome) {
        VehicleSubscriptionReceiptEntity entity = new VehicleSubscriptionReceiptEntity();
        entity.setId(71L);
        entity.setSubscriptionId(SUBSCRIPTION);
        entity.setPaymentId(PAYMENT);
        entity.setSource("SEPAY");
        entity.setReference("tx-1");
        entity.setInvoiceNo("SUB-61");
        entity.setAmountVnd(5_000L);
        entity.setCurrency("VND");
        entity.setPaidAt(NOW);
        entity.setReceivedAt(NOW);
        entity.setOutcome(outcome);
        return entity;
    }
}
