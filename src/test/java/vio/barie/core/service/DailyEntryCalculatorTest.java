package vio.barie.core.service;

import org.junit.jupiter.api.Test;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.dto.fee.calculation.FeeLine;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.fee.calculation.TimeRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vio.barie.core.service.FeeCalculatorTestSupport.*;

class DailyEntryCalculatorTest {
    private final DailyEntryCalculator calculator = new DailyEntryCalculator();

    @Test void aContinuousVisitorSessionCrossingMidnightHasTwoLocalDayCharges() {
        var lines = calculator.calculate(List.of(allocation(range("2026-10-03T20:00:00", "2026-10-04T08:00:00"), daily(1, 5000))), ZONE, null);
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(FeeLine::localDate)).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4));
        assertThat(lines.stream().mapToLong(FeeLine::subtotalVnd).sum()).isEqualTo(10_000);
    }

    @Test void anExitExactlyAtMidnightDoesNotIntersectTheNextDay() {
        var lines = calculator.calculate(List.of(allocation(range("2026-10-03T20:00:00", "2026-10-04T00:00:00"), daily(1, 5000))), ZONE, null);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().localDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test void aPositiveMicrosecondOnTheNextDayCountsThatDay() {
        TimeRange range = new TimeRange(instant("2026-10-03T23:59:59"), instant("2026-10-04T00:00:00").plusNanos(1000));
        assertThat(calculator.calculate(List.of(allocation(range, daily(1, 5000))), ZONE, null)).hasSize(2);
    }

    @Test void theExplicitParkingTimezoneControlsTheLocalDateBoundary() {
        TimeRange utcRange = new TimeRange(Instant.parse("2026-10-03T16:30:00Z"), Instant.parse("2026-10-03T17:30:00Z"));
        assertThat(calculator.calculate(List.of(allocation(utcRange, daily(1, 5000))), ZONE, null)).hasSize(2);
        assertThat(calculator.calculate(List.of(allocation(utcRange, daily(1, 5000))), ZoneOffset.UTC, null)).hasSize(1);
    }

    @Test void severalOutsideSegmentsInOneDayRequireAnApprovedCountingConvention() {
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), daily(1, 5000)),
                allocation(range("2026-10-05T17:00:00", "2026-10-05T19:00:00"), daily(1, 5000)));
        assertThatThrownBy(() -> calculator.calculate(allocations, ZONE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("SESSION_DAY or SEGMENT_DAY");
        var sessionDay = calculator.calculate(allocations, ZONE, new FeeRules(null, null, FeeRules.DailyGrouping.SESSION_DAY, null));
        var segmentDay = calculator.calculate(allocations, ZONE, new FeeRules(null, null, FeeRules.DailyGrouping.SEGMENT_DAY, null));
        assertThat(sessionDay).hasSize(1);
        assertThat(sessionDay.getFirst().ranges()).hasSize(2);
        assertThat(sessionDay.getFirst().subtotalVnd()).isEqualTo(5000);
        assertThat(segmentDay).hasSize(2);
        assertThat(segmentDay.stream().mapToLong(FeeLine::subtotalVnd).sum()).isEqualTo(10_000);
    }

    @Test void contiguousAllocationSlicesDoNotTurnIntoSeveralDailyEntries() {
        FeePolicyResponse tariff = daily(1, 5000);
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), tariff),
                allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), tariff));
        assertThat(calculator.calculate(allocations, ZONE, null)).hasSize(1);
    }

    @Test void severalRateVersionsOnOneDayRequireAPriceSelectionConvention() {
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), daily(1, 5000)),
                allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), daily(2, 7000)));
        assertThatThrownBy(() -> calculator.calculate(allocations, ZONE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("FIRST, HIGHEST or EACH_VERSION");
        var first = calculator.calculate(allocations, ZONE, new FeeRules(null, null, null, FeeRules.DailyPriceSelection.FIRST));
        var highest = calculator.calculate(allocations, ZONE, new FeeRules(null, null, null, FeeRules.DailyPriceSelection.HIGHEST));
        var each = calculator.calculate(allocations, ZONE, new FeeRules(null, null, null, FeeRules.DailyPriceSelection.EACH_VERSION));
        assertThat(first.getFirst().subtotalVnd()).isEqualTo(5000);
        assertThat(first.getFirst().tariff().id()).isEqualTo(1);
        assertThat(highest.getFirst().subtotalVnd()).isEqualTo(7000);
        assertThat(highest.getFirst().tariff().id()).isEqualTo(2);
        assertThat(each).hasSize(2);
        assertThat(each.stream().mapToLong(FeeLine::subtotalVnd).sum()).isEqualTo(12_000);
    }

    @Test void eachVersionCountsAVersionOnlyOnceWithinItsSessionDayGroup() {
        FeePolicyResponse first = daily(1, 5000);
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), first),
                allocation(range("2026-10-05T09:00:00", "2026-10-05T10:00:00"), daily(2, 7000)),
                allocation(range("2026-10-05T11:00:00", "2026-10-05T12:00:00"), first));
        var result = calculator.calculate(allocations, ZONE,
                new FeeRules(null, null, FeeRules.DailyGrouping.SESSION_DAY, FeeRules.DailyPriceSelection.EACH_VERSION));
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().ranges()).hasSize(2);
        assertThat(result.stream().mapToLong(FeeLine::subtotalVnd).sum()).isEqualTo(12_000);
    }

    @Test void anEmptyIntervalDoesNotCreateAnEntryAndZeroPricesStayZero() {
        Instant now = instant("2026-10-05T08:00:00");
        assertThat(calculator.calculate(List.of(allocation(new TimeRange(now, now), daily(1, 5000))), ZONE, null)).isEmpty();
        var zero = calculator.calculate(List.of(allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), daily(1, 0))), ZONE, null);
        assertThat(zero.getFirst().subtotalVnd()).isZero();
    }

    @Test void invalidPricesAndOverlappingAllocationsAreRejected() {
        TimeRange interval = range("2026-10-05T08:00:00", "2026-10-05T09:00:00");
        assertThatThrownBy(() -> calculator.calculate(List.of(allocation(interval, daily(1, -1))), ZONE, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> calculator.calculate(List.of(allocation(interval, daily(1, 5000)),
                allocation(interval, daily(1, 5000))), ZONE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("same instant twice");
    }
}
