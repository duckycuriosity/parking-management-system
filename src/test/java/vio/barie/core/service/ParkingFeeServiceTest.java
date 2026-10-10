package vio.barie.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.application.ParkingFeeSettings;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.*;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.utils.VehicleType;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Orchestration contracts; actual arithmetic and row locks have separate pure/PG coverage. */
class ParkingFeeServiceTest {
    private static final Instant ENTRY = Instant.parse("2026-10-09T01:00:00Z");
    private static final Instant NOW = ENTRY.plusSeconds(3600);
    private final ParkingSessionRepository sessions = mock(ParkingSessionRepository.class);
    private final FeeCalculationRepository calculations = mock(FeeCalculationRepository.class);
    private final VehicleSubscriptionRepository subscriptionRepository = mock(VehicleSubscriptionRepository.class);
    private final VehicleSubscriptionService subscriptions = mock(VehicleSubscriptionService.class);
    private final FeePolicyService policies = mock(FeePolicyService.class);
    private final ParkingFeeWorkflowGuard workflowGuard = mock(ParkingFeeWorkflowGuard.class);
    private final EntityManager em = mock(EntityManager.class);
    private ParkingFeeSettings settings;
    private ParkingFeeService service;
    private ParkingSessionEntity session;
    private FeeCalculationEntity captured;

    @BeforeEach
    void fixture() {
        settings = new ParkingFeeSettings();
        settings.setSessionTimeZone("Asia/Ho_Chi_Minh");
        settings.getParkingLotTimeZones().put(1L, "Asia/Ho_Chi_Minh");
        settings.setOutsidePricing(ParkingFeeSettings.OutsidePricing.ENTRY_VERSION);
        settings.setDailyGrouping(FeeRules.DailyGrouping.SESSION_DAY);
        settings.setDailyPriceSelection(FeeRules.DailyPriceSelection.FIRST);
        VehicleEntity vehicle = new VehicleEntity(); vehicle.setId(1L); vehicle.setVehicleType("CAR");
        ParkingLotEntity lot = new ParkingLotEntity(); lot.setId(1L);
        session = new ParkingSessionEntity(); session.setId(1L); session.setVehicle(vehicle);
        session.setParkingLot(lot); session.setEntryTime(LocalDateTime.of(2026, 10, 9, 8, 0));
        when(sessions.findById(1L)).thenReturn(Optional.of(session));
        service = new ParkingFeeService(sessions, calculations, subscriptionRepository, subscriptions,
                policies, new SubscriptionCoverageCalculator(),
                new FeeCalculator(new TimeBlockCalculator(), new DailyEntryCalculator()), settings, workflowGuard, em,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper().findAndRegisterModules());
    }

    private void quoteFixture() {
        when(subscriptionRepository.lockVehicleEntity(1L)).thenReturn(Optional.of(session.getVehicle()));
        when(sessions.lockForFee(1L)).thenReturn(Optional.of(session));
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, NOW)).thenReturn(List.of());
        when(calculations.saveAndFlush(any())).thenAnswer(call -> {
            captured = call.getArgument(0); captured.setId(10L); return captured;
        });
    }

    private void dailyPolicy() {
        when(policies.findForVisitor(1L, VehicleType.CAR, ENTRY)).thenReturn(FeePolicyResponse.builder()
                .id(1).parkingLotId(1L).vehicleType(VehicleType.CAR).name("Daily")
                .versionNo(1).effectiveFrom(ENTRY.minusSeconds(86400))
                .calculationMethod(FeeCalculationMethod.DAILY_ENTRY).dailyPriceVnd(5000L).build());
    }

    @Test
    void simulationCanShowAnEarlierCutoffWithoutPersistingOrAcquiringWriteLocks() {
        dailyPolicy();
        Instant cutoff = ENTRY.plusSeconds(1800);
        when(subscriptions.findPaidOverlapping(1L, 1L, ENTRY, cutoff)).thenReturn(List.of());
        ParkingFeeResponse result = service.simulate(new ParkingFeeQueryRequest(1L, cutoff));
        assertNull(result.calculationId()); assertEquals("SIMULATED", result.status());
        assertEquals(cutoff, result.calculationUntil()); assertEquals(5000, result.totalVnd());
        verifyNoInteractions(calculations, subscriptionRepository, em);
        verify(subscriptions, never()).lockPaidPeriodsForSettlement(anyLong(), anyLong(), any(), any());
    }

    @Test
    void simulationRejectsCutoffsOutsideActualSession() {
        assertThrows(BusinessException.class, () -> service.simulate(new ParkingFeeQueryRequest(1L, ENTRY.minusSeconds(1))));
        assertThrows(BusinessException.class, () -> service.simulate(new ParkingFeeQueryRequest(1L, NOW.plusSeconds(1))));
        verifyNoInteractions(calculations, policies, subscriptions);
    }

    @Test
    void quoteUsesBackendCutoffAndSupersedesOnlyUnsettledHistory() {
        quoteFixture(); dailyPolicy();
        FeeCalculationEntity older = new FeeCalculationEntity(); older.setId(9L); older.setStatus("QUOTED");
        older.setTotalVnd(123000);
        when(calculations.findBySessionIdAndStatusOrderByIdAsc(1L, "QUOTED")).thenReturn(List.of(older));
        Instant deadline = NOW.plusSeconds(600);
        ParkingFeeResponse result = service.quote(new ParkingFeeQuoteRequest(1L, deadline));
        assertEquals(NOW, result.calculationUntil()); assertEquals(deadline, result.validUntil());
        assertEquals("SUPERSEDED", older.getStatus()); assertEquals(123000, older.getTotalVnd());
        assertEquals("parking-fee-v1", result.snapshot().get("engineRevision").asText());
        assertEquals("Asia/Ho_Chi_Minh", result.snapshot().get("timezone").asText());
        assertEquals("Asia/Ho_Chi_Minh", result.snapshot().get("sessionTimeZone").asText());
        assertEquals(64, result.inputFingerprint().length());
    }

    @Test
    void payableQuoteRequiresExplicitFutureMicrosecondDeadline() {
        assertThrows(BusinessException.class, () -> service.quote(new ParkingFeeQuoteRequest(1L, null)));
        assertThrows(BusinessException.class, () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW)));
        assertThrows(BusinessException.class, () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600).plusNanos(1))));
        verifyNoInteractions(calculations, subscriptions, policies);
    }

    @Test
    void unknownLegacyOrLotTimezoneBlocksQuoting() {
        quoteFixture();
        settings.setSessionTimeZone(null);
        assertEquals("FEE_TIMEZONE_REQUIRED", assertThrows(BusinessException.class,
                () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)))).getCode());
        settings.setSessionTimeZone("Asia/Ho_Chi_Minh"); settings.getParkingLotTimeZones().clear();
        assertEquals("FEE_TIMEZONE_REQUIRED", assertThrows(BusinessException.class,
                () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)))).getCode());
        verify(calculations, never()).saveAndFlush(any());
    }

    @Test
    void allCoveredSessionNeedsNoVisitorPriceAndProducesExplainableZeroQuote() {
        quoteFixture();
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, NOW))
                .thenReturn(List.of(paid(ENTRY.minusSeconds(1))));
        ParkingFeeResponse result = service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)));
        assertEquals(0, result.totalVnd()); assertEquals("SUBSCRIPTION_OUTSIDE", result.snapshot().get("chargeContext").asText());
        assertTrue(result.snapshot().get("coverage").get("uncovered").isEmpty());
        assertEquals(1, result.snapshot().get("coverage").get("sources").size());
        verifyNoInteractions(policies);
    }

    @Test
    void newlyPaidPeriodCannotBeAppliedRetroactivelyWithoutAnApprovedConvention() {
        quoteFixture();
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, NOW))
                .thenReturn(List.of(paid(ENTRY.plusSeconds(1800))));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600))));
        assertEquals("FEE_RULE_DECISION_REQUIRED", exception.getCode());
        verifyNoInteractions(policies); verify(calculations, never()).saveAndFlush(any());
    }

    @Test
    void disconnectedOutsideIntervalsNeedAnApprovedDailyGroupingBeforeCharging() {
        quoteFixture(); dailyPolicy(); settings.setDailyGrouping(null);
        SubscriptionResponse partial = paid(ENTRY.minusSeconds(1));
        partial.setStartsAt(ENTRY.plusSeconds(1200)); partial.setEndsAt(ENTRY.plusSeconds(2400));
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, NOW)).thenReturn(List.of(partial));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600))));
        assertEquals("FEE_RULE_DECISION_REQUIRED", exception.getCode());
        verify(calculations, never()).saveAndFlush(any());
    }

    @Test
    void approvedPaymentTimeConventionClipsCoverageAndRecordsThatDecision() {
        quoteFixture(); dailyPolicy();
        settings.setEntitlementTiming(ParkingFeeSettings.EntitlementTiming.PAYMENT_TIME);
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, NOW))
                .thenReturn(List.of(paid(ENTRY.plusSeconds(1800))));
        ParkingFeeResponse result = service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)));
        assertEquals(5000, result.totalVnd());
        assertEquals(ENTRY.plusSeconds(1800).toString(), result.snapshot().get("coverage").get("covered").get(0).get("start").asText());
        assertEquals("PAYMENT_TIME", result.snapshot().get("entitlementTiming").asText());
    }

    @Test
    void entitlementPaidAfterSessionExitKeepsTheVisitorEntryPrice() {
        quoteFixture(); dailyPolicy();
        Instant cutoff = ENTRY.plusSeconds(1800);
        session.setExitTime(LocalDateTime.of(2026, 10, 9, 8, 30));
        settings.setOutsidePricing(ParkingFeeSettings.OutsidePricing.EFFECTIVE_AT_TIME);
        settings.setEntitlementTiming(ParkingFeeSettings.EntitlementTiming.PAYMENT_TIME);
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, cutoff))
                .thenReturn(List.of(paid(ENTRY.plusSeconds(2700))));
        ParkingFeeResponse result = service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)));
        assertEquals("VISITOR", result.snapshot().get("chargeContext").asText());
        assertEquals(5000, result.totalVnd()); assertTrue(result.snapshot().get("coverage").get("covered").isEmpty());
        verify(policies).findForVisitor(1L, VehicleType.CAR, ENTRY);
        verify(policies, never()).findOverlapping(anyLong(), any(), any(), any());
    }

    @Test
    void zeroDurationPaymentRequiresAnExplicitDecision() {
        quoteFixture(); session.setExitTime(session.getEntryTime());
        assertEquals("FEE_RULE_DECISION_REQUIRED", assertThrows(BusinessException.class,
                () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)))).getCode());
        settings.setAllowZeroDurationSettlement(true);
        assertEquals(0, service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600))).totalVnd());
        verifyNoInteractions(policies);
        verify(subscriptions, never()).lockPaidPeriodsForSettlement(anyLong(), anyLong(), any(), any());
    }

    @Test
    void acquiringSessionLockRechecksScopeRatherThanUsingPreliminaryReads() {
        quoteFixture();
        ParkingSessionEntity different = new ParkingSessionEntity(); different.setId(1L);
        different.setVehicle(session.getVehicle());
        ParkingLotEntity other = new ParkingLotEntity(); other.setId(2L); different.setParkingLot(other);
        when(sessions.lockForFee(1L)).thenReturn(Optional.of(different));
        assertThrows(BusinessException.class, () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600))));
        verify(em).refresh(different); verifyNoInteractions(policies); verify(calculations, never()).saveAndFlush(any());
    }

    @Test
    void settledSessionIsNotRepricedOrSuperseded() {
        quoteFixture();
        when(calculations.existsBySessionIdAndStatus(1L, "SETTLED")).thenReturn(true);
        assertEquals("FEE_SESSION_ALREADY_SETTLED", assertThrows(BusinessException.class,
                () -> service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)))).getCode());
        verifyNoInteractions(policies); verify(calculations, never()).findBySessionIdAndStatusOrderByIdAsc(anyLong(), anyString());
        verify(calculations, never()).saveAndFlush(any());
    }

    @Test
    void settlementRechecksEntitlementButKeepsTheFrozenPriceVersion() {
        quoteFixture(); dailyPolicy();
        service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)));
        when(calculations.findById(10L)).thenReturn(Optional.of(captured));
        when(calculations.lockById(10L)).thenReturn(Optional.of(captured));
        clearInvocations(policies);
        assertEquals("QUOTED", service.lockForSettlement(10L).getStatus());
        verifyNoInteractions(policies);
        settings.setEntitlementTiming(ParkingFeeSettings.EntitlementTiming.PERIOD_START);
        when(subscriptions.lockPaidPeriodsForSettlement(1L, 1L, ENTRY, NOW))
                .thenReturn(List.of(paid(ENTRY.minusSeconds(1))));
        assertEquals("SUPERSEDED", service.lockForSettlement(10L).getStatus());
        assertEquals(5000, captured.getTotalVnd());
    }

    @Test
    void settlementRejectsScopeChangedDuringLockAcquisitionBeforeLockingAnotherVehiclesPeriods() {
        quoteFixture(); dailyPolicy();
        service.quote(new ParkingFeeQuoteRequest(1L, NOW.plusSeconds(600)));
        when(calculations.findById(10L)).thenReturn(Optional.of(captured));
        when(calculations.lockById(10L)).thenReturn(Optional.of(captured));
        VehicleEntity quotedVehicle = session.getVehicle();
        VehicleEntity preliminaryVehicle = new VehicleEntity(); preliminaryVehicle.setId(2L);
        preliminaryVehicle.setVehicleType("CAR"); session.setVehicle(preliminaryVehicle);
        when(subscriptionRepository.lockVehicleEntity(2L)).thenReturn(Optional.of(preliminaryVehicle));
        doAnswer(call -> { session.setVehicle(quotedVehicle); return null; }).when(em).refresh(session);
        clearInvocations(subscriptions);
        assertEquals("SUPERSEDED", service.lockForSettlement(10L).getStatus());
        verify(subscriptionRepository).lockVehicleEntity(2L);
        verify(subscriptions, never()).lockPaidPeriodsForSettlement(anyLong(), anyLong(), any(), any());
        assertEquals(5000, captured.getTotalVnd());
    }

    private SubscriptionResponse paid(Instant paidAt) {
        return SubscriptionResponse.builder().id(7L).packageId(4L).packageVersionNo(1)
                .vehicleId(1L).parkingLotId(1L).vehicleType(VehicleType.CAR)
                .status(VehicleSubscriptionStatus.PAID).coverageMode(SubscriptionCoverageMode.ALL_DAY)
                .startsAt(ENTRY.minusSeconds(86400)).endsAt(NOW.plusSeconds(86400)).paidAt(paidAt).build();
    }
}
