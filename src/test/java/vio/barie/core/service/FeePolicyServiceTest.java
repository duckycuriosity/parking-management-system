package vio.barie.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.FeePolicyApplyRequest;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.dto.fee.FeeTimeBandRequest;
import vio.barie.core.entity.FeePolicyEntity;
import vio.barie.core.entity.FeeTimeBandEntity;
import vio.barie.core.repository.FeePolicyRepository;
import vio.barie.core.utils.VehicleType;

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
 * Business-level unit checks. PostgreSQL exclusion, transaction rollback, and
 * concurrent publishing must be verified separately against a real database.
 */
@ExtendWith(MockitoExtension.class)
class FeePolicyServiceTest {

    private static final long LOT = 33L;
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    @Mock
    private FeePolicyRepository repository;

    private FeePolicyService service;

    @BeforeEach
    void setUp() {
        service = new FeePolicyService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(repository.parkingLotExists(anyLong())).thenReturn(true);
        lenient().when(repository.lockParkingLot(anyLong()))
                .thenAnswer(call -> Optional.of(call.<Long>getArgument(0)));
        lenient().when(repository.saveAndFlush(any(FeePolicyEntity.class))).thenAnswer(call -> {
            FeePolicyEntity entity = call.getArgument(0);
            entity.setId(102);
            return entity;
        });
    }

    @Test
    void firstVersionUsesServerTimeAndEmptyReservedJson() {
        FeePolicyResponse response = service.apply(dailyRequest());

        ArgumentCaptor<FeePolicyEntity> captor = ArgumentCaptor.forClass(FeePolicyEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        FeePolicyEntity saved = captor.getValue();
        assertEquals(1, saved.getVersionNo());
        assertEquals(NOW, saved.getEffectiveFrom());
        assertNull(saved.getEffectiveTo());
        assertEquals(5_000L, saved.getDailyPriceVnd());
        assertTrue(saved.getPricingRules().isEmpty());
        assertTrue(saved.getTimeBands().isEmpty());
        assertEquals(102, response.getId());
    }

    @Test
    void serverTimeIsNormalizedToPostgresqlMicrosecondPrecision() {
        Instant preciseNow = Instant.parse("2026-10-09T02:00:00.123456789Z");
        service = new FeePolicyService(repository, Clock.fixed(preciseNow, ZoneOffset.UTC));

        FeePolicyResponse response = service.apply(dailyRequest());

        assertEquals(Instant.parse("2026-10-09T02:00:00.123456Z"), response.getEffectiveFrom());
    }

    @Test
    void appendClosesPreviousVersionWithoutChangingItsPricesOrReservedJson() {
        FeePolicyEntity old = dailyPolicy(101, 1, NOW.minusSeconds(86_400), null, 5_000L);
        Map<String, Object> reserved = Map.of("future_contract", "preserve unchanged");
        old.setPricingRules(reserved);
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR)).thenReturn(List.of(old));
        FeePolicyApplyRequest request = dailyRequest();
        request.setDailyPriceVnd(7_000L);
        request.setEffectiveFrom(NOW.plusSeconds(86_400));

        FeePolicyResponse response = service.apply(request);

        assertEquals(request.getEffectiveFrom(), old.getEffectiveTo());
        assertEquals(5_000L, old.getDailyPriceVnd());
        assertSame(reserved, old.getPricingRules());
        assertEquals(2, response.getVersionNo());
        assertNotEquals(old.getId(), response.getId());
        assertEquals(7_000L, response.getDailyPriceVnd());
        InOrder order = inOrder(repository);
        order.verify(repository).lockParkingLot(LOT);
        order.verify(repository).findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR);
        order.verify(repository).flush();
        order.verify(repository).saveAndFlush(any(FeePolicyEntity.class));
    }

    @Test
    void publishingBlockPricesCreatesNewBandsWithoutEditingHistoricalBands() {
        FeePolicyEntity old = dailyPolicy(101, 1, NOW.minusSeconds(86_400), null, 0L);
        old.setCalculationMethod(FeeCalculationMethod.TIME_BLOCK);
        old.setDailyPriceVnd(null);
        old.setRemainderThresholdMinutes(10);
        FeeTimeBandEntity historicalBand = new FeeTimeBandEntity();
        historicalBand.setName("All day");
        historicalBand.setStartTime(LocalTime.MIDNIGHT);
        historicalBand.setEndTime(LocalTime.MIDNIGHT);
        historicalBand.setFirstBlockMinutes(60);
        historicalBand.setNextBlockMinutes(60);
        historicalBand.setFirstBlockPriceVnd(5_000L);
        historicalBand.setNextBlockPriceVnd(3_000L);
        old.addTimeBand(historicalBand);
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR)).thenReturn(List.of(old));

        FeePolicyResponse result = service.apply(blockRequest());

        assertEquals(2, result.getTimeBands().size());
        assertEquals(1, old.getTimeBands().size());
        assertSame(historicalBand, old.getTimeBands().get(0));
        assertSame(old, historicalBand.getFeePolicy());
        assertEquals(5_000L, historicalBand.getFirstBlockPriceVnd());
        assertEquals(3_000L, historicalBand.getNextBlockPriceVnd());
    }

    @Test
    void newVersionMustAppendAfterAlreadyScheduledVersion() {
        Instant scheduled = NOW.plusSeconds(86_400);
        FeePolicyEntity first = dailyPolicy(101, 1, NOW.minusSeconds(86_400), scheduled, 5_000L);
        FeePolicyEntity second = dailyPolicy(102, 2, scheduled, null, 7_000L);
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR)).thenReturn(List.of(first, second));
        FeePolicyApplyRequest request = dailyRequest();
        request.setEffectiveFrom(scheduled.plusSeconds(86_400));
        when(repository.saveAndFlush(any(FeePolicyEntity.class))).thenAnswer(call -> {
            FeePolicyEntity policy = call.getArgument(0);
            policy.setId(103);
            return policy;
        });

        FeePolicyResponse response = service.apply(request);

        assertEquals(3, response.getVersionNo());
        assertEquals(scheduled, first.getEffectiveTo());
        assertEquals(request.getEffectiveFrom(), second.getEffectiveTo());
    }

    @Test
    void cannotInsertBeforeOrAtLastScheduledStart() {
        Instant scheduled = NOW.plusSeconds(86_400);
        FeePolicyEntity latest = dailyPolicy(101, 1, scheduled, null, 5_000L);
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR)).thenReturn(List.of(latest));

        for (Instant start : List.of(NOW, scheduled)) {
            FeePolicyApplyRequest request = dailyRequest();
            request.setEffectiveFrom(start);
            assertThrows(BusinessException.class, () -> service.apply(request));
            assertNull(latest.getEffectiveTo());
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void anotherVehicleTypeStartsItsOwnVersionSequence() {
        FeePolicyApplyRequest request = dailyRequest();
        request.setVehicleType(VehicleType.MOTORBIKE);
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.MOTORBIKE)).thenReturn(List.of());

        FeePolicyResponse result = service.apply(request);

        assertEquals(1, result.getVersionNo());
        verify(repository, never()).findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR);
    }

    @Test
    void normalDayAndOvernightBandsAreStoredAgainstNewPolicy() {
        FeePolicyApplyRequest request = blockRequest();
        request.getTimeBands().get(1).setSortOrder(null);

        FeePolicyResponse response = service.apply(request);

        ArgumentCaptor<FeePolicyEntity> captor = ArgumentCaptor.forClass(FeePolicyEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        FeePolicyEntity policy = captor.getValue();
        assertEquals(2, policy.getTimeBands().size());
        assertEquals(2, response.getTimeBands().size());
        assertNull(policy.getDailyPriceVnd());
        assertEquals(10, policy.getRemainderThresholdMinutes());
        assertTrue(policy.getPricingRules().isEmpty());
        assertTrue(policy.getTimeBands().stream().allMatch(band -> band.getFeePolicy() == policy));
        FeeTimeBandEntity night = policy.getTimeBands().stream()
                .filter(band -> band.getName().equals("Night")).findFirst().orElseThrow();
        assertEquals(LocalTime.of(18, 0), night.getStartTime());
        assertEquals(LocalTime.of(6, 0), night.getEndTime());
        assertEquals(15_000L, night.getFirstBlockPriceVnd());
        assertEquals(8_000L, night.getNextBlockPriceVnd());
        assertEquals(0, night.getSortOrder());
    }

    @Test
    void equalStartAndEndMeansOneFullDayBand() {
        FeePolicyApplyRequest request = blockRequest();
        request.setTimeBands(List.of(band("All day", "06:00", "06:00", 20_000L, 10_000L)));
        request.setRemainderThresholdMinutes(0);

        FeePolicyResponse response = service.apply(request);

        assertEquals(1, response.getTimeBands().size());
        assertEquals(0, response.getRemainderThresholdMinutes());
    }

    @Test
    void subMicrosecondBandTimesAreRejectedBeforeDatabaseRounding() {
        FeePolicyApplyRequest request = blockRequest();
        LocalTime boundary = LocalTime.of(18, 0, 0, 1);
        request.getTimeBands().get(0).setEndTime(boundary);
        request.getTimeBands().get(1).setStartTime(boundary);

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).lockParkingLot(anyLong());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void microsecondBandTimesRemainExactInPublishedConfiguration() {
        FeePolicyApplyRequest request = blockRequest();
        LocalTime boundary = LocalTime.of(18, 0, 0, 123_456_000);
        request.getTimeBands().get(0).setEndTime(boundary);
        request.getTimeBands().get(1).setStartTime(boundary);

        FeePolicyResponse response = service.apply(request);

        assertEquals(boundary, response.getTimeBands().get(0).getEndTime());
        assertEquals(boundary, response.getTimeBands().get(1).getStartTime());
    }

    @Test
    void fullDayBandCannotCoexistWithAnotherBand() {
        FeePolicyApplyRequest request = blockRequest();
        request.setTimeBands(List.of(
                band("All day", "00:00", "00:00", 20_000L, 10_000L),
                band("Extra", "06:00", "18:00", 20_000L, 10_000L)));

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void gapAtBandBoundaryIsRejectedEvenWhenDisplayOrderIsValid() {
        FeePolicyApplyRequest request = blockRequest();
        request.getTimeBands().get(0).setEndTime(LocalTime.of(17, 59));

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void overlapAtBandBoundaryIsRejected() {
        FeePolicyApplyRequest request = blockRequest();
        request.getTimeBands().get(0).setEndTime(LocalTime.of(18, 1));

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "invalid daily configuration {index}")
    @MethodSource("invalidDailyChanges")
    void invalidDailyConfigurationIsRejected(Consumer<FeePolicyApplyRequest> change) {
        FeePolicyApplyRequest request = dailyRequest();
        change.accept(request);

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).saveAndFlush(any());
    }

    static Stream<Consumer<FeePolicyApplyRequest>> invalidDailyChanges() {
        return Stream.of(
                request -> request.setParkingLotId(null),
                request -> request.setParkingLotId(0L),
                request -> request.setVehicleType(null),
                request -> request.setVehicleType(VehicleType.TRUCK),
                request -> request.setName("  "),
                request -> request.setName("x".repeat(256)),
                request -> request.setCalculationMethod(null),
                request -> request.setDailyPriceVnd(null),
                request -> request.setDailyPriceVnd(-1L),
                request -> request.setRemainderThresholdMinutes(0),
                request -> request.setTimeBands(List.of(band("Unexpected", "00:00", "00:00", 0L, 0L))),
                request -> request.setEffectiveFrom(NOW.minusSeconds(1))
        );
    }

    @ParameterizedTest(name = "invalid block configuration {index}")
    @MethodSource("invalidBlockChanges")
    void invalidBlockConfigurationIsRejected(Consumer<FeePolicyApplyRequest> change) {
        FeePolicyApplyRequest request = blockRequest();
        change.accept(request);

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).saveAndFlush(any());
    }

    static Stream<Consumer<FeePolicyApplyRequest>> invalidBlockChanges() {
        return Stream.of(
                request -> request.setDailyPriceVnd(0L),
                request -> request.setRemainderThresholdMinutes(null),
                request -> request.setRemainderThresholdMinutes(-1),
                request -> request.setTimeBands(List.of()),
                request -> request.setTimeBands(null),
                request -> request.getTimeBands().get(0).setName("x".repeat(101)),
                request -> request.getTimeBands().get(0).setStartTime(null),
                request -> request.getTimeBands().get(0).setEndTime(null),
                request -> request.getTimeBands().get(0).setFirstBlockMinutes(0),
                request -> request.getTimeBands().get(0).setNextBlockMinutes(-1),
                request -> request.getTimeBands().get(0).setFirstBlockPriceVnd(-1L),
                request -> request.getTimeBands().get(0).setNextBlockPriceVnd(null)
        );
    }

    @Test
    void zeroDailyPriceAndAbsentBandListAreAccepted() {
        FeePolicyApplyRequest request = dailyRequest();
        request.setDailyPriceVnd(0L);
        request.setTimeBands(null);

        assertEquals(0L, service.apply(request).getDailyPriceVnd());
    }

    @Test
    void missingParkingLotIsRejectedBeforePublishing() {
        when(repository.lockParkingLot(LOT)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.apply(dailyRequest()));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void overlappingHistoricalTimelineCannotBeExtended() {
        FeePolicyEntity first = dailyPolicy(101, 1, NOW.minusSeconds(86_400), NOW.plusSeconds(1), 5_000L);
        FeePolicyEntity second = dailyPolicy(102, 2, NOW, null, 7_000L);
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR)).thenReturn(List.of(first, second));
        FeePolicyApplyRequest request = dailyRequest();
        request.setEffectiveFrom(NOW.plusSeconds(86_400));

        assertThrows(BusinessException.class, () -> service.apply(request));
        verify(repository, never()).saveAndFlush(any());
        assertNull(second.getEffectiveTo());
    }

    @Test
    void visitorLookupUsesEntryTimeEvenAfterNewPriceBecomesEffective() {
        Instant entered = Instant.parse("2026-10-01T10:00:00Z");
        Instant changeAt = Instant.parse("2026-10-10T00:00:00Z");
        FeePolicyEntity first = dailyPolicy(101, 1, Instant.parse("2026-09-01T00:00:00Z"), changeAt, 5_000L);
        when(repository.findEffectiveAt(LOT, VehicleType.CAR, entered)).thenReturn(List.of(first));

        FeePolicyResponse result = service.findForVisitor(LOT, VehicleType.CAR, entered);

        assertEquals(101, result.getId());
        assertEquals(5_000L, result.getDailyPriceVnd());
        verify(repository).findEffectiveAt(LOT, VehicleType.CAR, entered);
        verify(repository, never()).findEffectiveAt(LOT, VehicleType.CAR, NOW);
    }

    @Test
    void currentLookupUsesInjectedClock() {
        FeePolicyEntity current = dailyPolicy(101, 1, NOW.minusSeconds(1), null, 5_000L);
        when(repository.findEffectiveAt(LOT, VehicleType.CAR, NOW)).thenReturn(List.of(current));

        assertEquals(101, service.findCurrent(LOT, VehicleType.CAR).getId());
        verify(repository).findEffectiveAt(LOT, VehicleType.CAR, NOW);
    }

    @Test
    void noEffectivePriceRaisesExplicitBusinessError() {
        when(repository.findEffectiveAt(LOT, VehicleType.CAR, NOW)).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.findAt(LOT, VehicleType.CAR, NOW));
    }

    @Test
    void duplicateEffectiveMatchesAreNotSilentlyResolvedByVersionNumber() {
        FeePolicyEntity first = dailyPolicy(101, 1, NOW.minusSeconds(2), null, 5_000L);
        FeePolicyEntity second = dailyPolicy(102, 2, NOW.minusSeconds(1), null, 7_000L);
        when(repository.findEffectiveAt(LOT, VehicleType.CAR, NOW)).thenReturn(List.of(first, second));

        assertThrows(BusinessException.class, () -> service.findAt(LOT, VehicleType.CAR, NOW));
    }

    @Test
    void overlappingLookupReturnsContiguousVersionsForRequestedInterval() {
        Instant start = NOW.minusSeconds(86_400);
        Instant changeAt = NOW;
        Instant end = NOW.plusSeconds(86_400);
        FeePolicyEntity first = dailyPolicy(101, 1, start.minusSeconds(1), changeAt, 5_000L);
        FeePolicyEntity second = dailyPolicy(102, 2, changeAt, null, 7_000L);
        when(repository.findOverlapping(LOT, VehicleType.CAR, start, end)).thenReturn(List.of(first, second));

        List<FeePolicyResponse> result = service.findOverlapping(LOT, VehicleType.CAR, start, end);

        assertEquals(List.of(101, 102), result.stream().map(FeePolicyResponse::getId).toList());
    }

    @Test
    void intervalLookupRejectsGapInsideRequestedRange() {
        Instant start = NOW.minusSeconds(86_400);
        Instant end = NOW.plusSeconds(86_400);
        FeePolicyEntity first = dailyPolicy(101, 1, start, NOW.minusSeconds(1), 5_000L);
        FeePolicyEntity second = dailyPolicy(102, 2, NOW, null, 7_000L);
        when(repository.findOverlapping(LOT, VehicleType.CAR, start, end)).thenReturn(List.of(first, second));

        assertThrows(BusinessException.class,
                () -> service.findOverlapping(LOT, VehicleType.CAR, start, end));
    }

    @Test
    void intervalLookupRequiresCoverageAtBothEnds() {
        Instant start = NOW.minusSeconds(86_400);
        Instant end = NOW.plusSeconds(86_400);
        FeePolicyEntity missingStart = dailyPolicy(101, 1, start.plusSeconds(1), null, 5_000L);
        when(repository.findOverlapping(LOT, VehicleType.CAR, start, end)).thenReturn(List.of(missingStart));
        assertThrows(BusinessException.class,
                () -> service.findOverlapping(LOT, VehicleType.CAR, start, end));

        FeePolicyEntity missingEnd = dailyPolicy(101, 1, start, end.minusSeconds(1), 5_000L);
        when(repository.findOverlapping(LOT, VehicleType.CAR, start, end)).thenReturn(List.of(missingEnd));
        assertThrows(BusinessException.class,
                () -> service.findOverlapping(LOT, VehicleType.CAR, start, end));
    }

    @Test
    void historyContainsOrderedCompleteVersionsWithoutReturningReservedJson() {
        FeePolicyEntity first = dailyPolicy(101, 1, NOW.minusSeconds(86_400), NOW, 5_000L);
        FeePolicyEntity second = dailyPolicy(102, 2, NOW, null, 7_000L);
        first.setPricingRules(Map.of("reserved", "ignored"));
        when(repository.findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                LOT, VehicleType.CAR)).thenReturn(List.of(first, second));

        List<FeePolicyResponse> history = service.findHistory(LOT, VehicleType.CAR);

        assertEquals(List.of(1, 2), history.stream().map(FeePolicyResponse::getVersionNo).toList());
        assertEquals(List.of(5_000L, 7_000L), history.stream().map(FeePolicyResponse::getDailyPriceVnd).toList());
        assertEquals(NOW, history.get(0).getEffectiveTo());
        assertNull(history.get(1).getEffectiveTo());
    }

    @Test
    void emptyOrReversedLookupIntervalIsRejected() {
        assertThrows(BusinessException.class,
                () -> service.findOverlapping(LOT, VehicleType.CAR, NOW, NOW));
        assertThrows(BusinessException.class,
                () -> service.findOverlapping(LOT, VehicleType.CAR, NOW, NOW.minusSeconds(1)));
    }

    @Test
    void idLookupReportsMissingPublishedPolicy() {
        when(repository.findById(999)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getById(999));
    }

    private static FeePolicyApplyRequest dailyRequest() {
        FeePolicyApplyRequest request = new FeePolicyApplyRequest();
        request.setParkingLotId(LOT);
        request.setVehicleType(VehicleType.CAR);
        request.setName("Visitor tariff");
        request.setCalculationMethod(FeeCalculationMethod.DAILY_ENTRY);
        request.setDailyPriceVnd(5_000L);
        return request;
    }

    private static FeePolicyApplyRequest blockRequest() {
        FeePolicyApplyRequest request = dailyRequest();
        request.setCalculationMethod(FeeCalculationMethod.TIME_BLOCK);
        request.setDailyPriceVnd(null);
        request.setRemainderThresholdMinutes(10);
        request.setTimeBands(List.of(
                band("Day", "06:00", "18:00", 20_000L, 10_000L),
                band("Night", "18:00", "06:00", 15_000L, 8_000L)));
        return request;
    }

    private static FeeTimeBandRequest band(String name, String start, String end, long first, long next) {
        FeeTimeBandRequest band = new FeeTimeBandRequest();
        band.setName(name);
        band.setStartTime(LocalTime.parse(start));
        band.setEndTime(LocalTime.parse(end));
        band.setFirstBlockMinutes(60);
        band.setFirstBlockPriceVnd(first);
        band.setNextBlockMinutes(60);
        band.setNextBlockPriceVnd(next);
        return band;
    }

    private static FeePolicyEntity dailyPolicy(int id, int version, Instant start, Instant end, long price) {
        FeePolicyEntity policy = new FeePolicyEntity();
        policy.setId(id);
        policy.setParkingLotId(LOT);
        policy.setVehicleType(VehicleType.CAR);
        policy.setName("Visitor tariff");
        policy.setVersionNo(version);
        policy.setEffectiveFrom(start);
        policy.setEffectiveTo(end);
        policy.setCalculationMethod(FeeCalculationMethod.DAILY_ENTRY);
        policy.setDailyPriceVnd(price);
        return policy;
    }
}
