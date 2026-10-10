package vio.barie.core.service;

import org.junit.jupiter.api.Test;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.calculation.CoverageResult;
import vio.barie.core.dto.fee.calculation.TimeRange;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.dto.subscription.SubscriptionScheduleResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vio.barie.core.service.FeeCalculatorTestSupport.*;

class SubscriptionCoverageCalculatorTest {
    private final SubscriptionCoverageCalculator calculator = new SubscriptionCoverageCalculator();

    @Test void noPaidPeriodsLeavesTheCompleteSessionUncovered() {
        TimeRange session = range("2026-10-05T07:00:00", "2026-10-05T19:00:00");
        CoverageResult result = calculator.calculate(session, List.of(), ZONE);
        assertThat(result.covered()).isEmpty();
        assertThat(result.uncovered()).containsExactly(session);
    }

    @Test void unpaidOrdersNeverGrantCoverage() {
        TimeRange session = range("2026-10-05T07:00:00", "2026-10-05T19:00:00");
        List<SubscriptionResponse> periods = new ArrayList<>();
        for (VehicleSubscriptionStatus status : List.of(VehicleSubscriptionStatus.PENDING_PAYMENT,
                VehicleSubscriptionStatus.CANCELLED, VehicleSubscriptionStatus.PAYMENT_EXCEPTION)) {
            SubscriptionResponse period = paid(1, session, SubscriptionCoverageMode.ALL_DAY);
            period.setStatus(status);
            periods.add(period);
        }
        assertThat(calculator.calculate(session, periods, ZONE).uncovered()).containsExactly(session);
    }

    @Test void allDayCoverageIsClippedToSessionAndThePurchasedPeriod() {
        TimeRange session = range("2026-10-05T07:00:00", "2026-10-05T19:00:00");
        TimeRange periodRange = range("2026-10-05T08:00:00", "2026-10-05T17:00:00");
        CoverageResult result = calculator.calculate(session,
                List.of(paid(51, periodRange, SubscriptionCoverageMode.ALL_DAY)), ZONE);
        assertThat(result.covered()).containsExactly(periodRange);
        assertThat(result.uncovered()).containsExactly(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"),
                range("2026-10-05T17:00:00", "2026-10-05T19:00:00"));
        assertThat(result.sources().getFirst().subscriptionId()).isEqualTo(51);
        assertThat(result.sources().getFirst().packageId()).isEqualTo(41);
    }

    @Test void weeklyCoverageIntersectsTheEntitlementSchedule() {
        TimeRange session = range("2026-10-05T07:00:00", "2026-10-05T19:00:00");
        SubscriptionResponse period = paid(1, session, SubscriptionCoverageMode.WEEKLY);
        period.setSchedules(List.of(schedule(1, "08:00", "17:00")));
        CoverageResult result = calculator.calculate(session, List.of(period), ZONE);
        assertThat(result.covered()).containsExactly(range("2026-10-05T08:00:00", "2026-10-05T17:00:00"));
        assertThat(result.sources().getFirst().weekday()).isEqualTo(1);
        assertThat(result.sources().getFirst().scheduleStartTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test void sundayCrossingMidnightCoversMondayAndKeepsItsOriginalWeekday() {
        TimeRange session = range("2026-10-05T00:00:00", "2026-10-05T03:00:00");
        SubscriptionResponse period = paid(1, range("2026-10-04T00:00:00", "2026-10-06T00:00:00"),
                SubscriptionCoverageMode.WEEKLY);
        period.setSchedules(List.of(schedule(7, "22:00", "02:00")));
        CoverageResult result = calculator.calculate(session, List.of(period), ZONE);
        assertThat(result.covered()).containsExactly(range("2026-10-05T00:00:00", "2026-10-05T02:00:00"));
        assertThat(result.uncovered()).containsExactly(range("2026-10-05T02:00:00", "2026-10-05T03:00:00"));
        assertThat(result.sources().getFirst().weekday()).isEqualTo(7);
    }

    @Test void equalWeeklyClocksMeanTwentyFourHoursFromTheNamedWeekday() {
        TimeRange session = range("2026-10-05T07:00:00", "2026-10-06T09:00:00");
        SubscriptionResponse period = paid(1, session, SubscriptionCoverageMode.WEEKLY);
        period.setSchedules(List.of(schedule(1, "08:00", "08:00")));
        CoverageResult result = calculator.calculate(session, List.of(period), ZONE);
        assertThat(result.covered()).containsExactly(range("2026-10-05T08:00:00", "2026-10-06T08:00:00"));
        assertThat(result.uncovered()).hasSize(2);
    }

    @Test void unionMergesOverlapAndAdjacentPeriodsWithoutLosingSourceEvidence() {
        TimeRange session = range("2026-10-05T07:00:00", "2026-10-05T19:00:00");
        List<SubscriptionResponse> periods = List.of(
                paid(3, range("2026-10-05T15:00:00", "2026-10-05T19:00:00"), SubscriptionCoverageMode.ALL_DAY),
                paid(1, range("2026-10-05T07:00:00", "2026-10-05T12:00:00"), SubscriptionCoverageMode.ALL_DAY),
                paid(2, range("2026-10-05T10:00:00", "2026-10-05T15:00:00"), SubscriptionCoverageMode.ALL_DAY));
        CoverageResult result = calculator.calculate(session, periods, ZONE);
        assertThat(result.covered()).containsExactly(session);
        assertThat(result.uncovered()).isEmpty();
        assertThat(result.sources()).hasSize(3);
        assertThat(calculator.calculate(session, periods.reversed(), ZONE)).isEqualTo(result);
    }

    @Test void aPeriodEndingAtTheSessionStartDoesNotCoverItsFirstInstant() {
        TimeRange session = range("2026-10-05T08:00:00", "2026-10-05T09:00:00");
        SubscriptionResponse prior = paid(1, range("2026-10-05T07:00:00", "2026-10-05T08:00:00"),
                SubscriptionCoverageMode.ALL_DAY);
        assertThat(calculator.calculate(session, List.of(prior), ZONE).uncovered()).containsExactly(session);
    }

    @Test void missingWeeklyScheduleProducesAnErrorInsteadOfFreeParking() {
        TimeRange session = range("2026-10-05T08:00:00", "2026-10-05T09:00:00");
        assertThatThrownBy(() -> calculator.calculate(session,
                List.of(paid(1, session, SubscriptionCoverageMode.WEEKLY)), ZONE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no entitlement schedule");
    }

    @Test void timezoneAndValidPaidPeriodAreRequired() {
        TimeRange session = range("2026-10-05T08:00:00", "2026-10-05T09:00:00");
        assertThatThrownBy(() -> calculator.calculate(session, List.of(), null)).isInstanceOf(BusinessException.class);
        SubscriptionResponse malformed = paid(1, session, SubscriptionCoverageMode.ALL_DAY);
        malformed.setEndsAt(malformed.getStartsAt());
        assertThatThrownBy(() -> calculator.calculate(session, List.of(malformed), ZONE)).isInstanceOf(BusinessException.class);
    }

    @Test void aZeroDurationIntervalHasNoCoveredOrUncoveredTime() {
        Instant now = instant("2026-10-05T08:00:00");
        CoverageResult result = calculator.calculate(new TimeRange(now, now), List.of(), ZONE);
        assertThat(result.sources()).isEmpty();
        assertThat(result.covered()).isEmpty();
        assertThat(result.uncovered()).isEmpty();
    }

    @Test void missingAndAmbiguousDstLocalClocksFailExplicitly() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        TimeRange session = new TimeRange(Instant.parse("2026-03-29T00:00:00Z"), Instant.parse("2026-03-29T05:00:00Z"));
        SubscriptionResponse period = paid(1, session, SubscriptionCoverageMode.WEEKLY);
        period.setSchedules(List.of(schedule(7, "02:30", "04:00")));
        assertThatThrownBy(() -> calculator.calculate(session, List.of(period), berlin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("missing or ambiguous");
    }

    @Test void partitionPropertyHoldsForUnsortedOverlappingPeriods() {
        Instant base = instant("2026-10-05T00:00:00");
        TimeRange session = new TimeRange(base, base.plusSeconds(1000));
        Random random = new Random(42);
        for (int example = 0; example < 80; example++) {
            List<SubscriptionResponse> periods = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                long from = random.nextInt(1400) - 200;
                long until = from + 1 + random.nextInt(500);
                periods.add(paid(index + 1, new TimeRange(base.plusSeconds(from), base.plusSeconds(until)),
                        SubscriptionCoverageMode.ALL_DAY));
            }
            CoverageResult result = calculator.calculate(session, periods, ZONE);
            Duration measured = Duration.ZERO;
            List<TimeRange> partition = new ArrayList<>(result.covered());
            partition.addAll(result.uncovered());
            partition.sort(FeeCalculationSupport.RANGE_ORDER);
            Instant cursor = session.start();
            for (TimeRange interval : partition) {
                assertThat(interval.start()).isEqualTo(cursor);
                measured = measured.plus(interval.duration());
                cursor = interval.end();
            }
            assertThat(cursor).isEqualTo(session.end());
            assertThat(measured).isEqualTo(session.duration());
        }
    }

    private SubscriptionResponse paid(long id, TimeRange period, SubscriptionCoverageMode mode) {
        return SubscriptionResponse.builder().id(id).vehicleId(12L).parkingLotId(33L).packageId(41L)
                .packageVersionNo(2).status(VehicleSubscriptionStatus.PAID).startsAt(period.start()).endsAt(period.end())
                .coverageMode(mode).build();
    }
    private SubscriptionScheduleResponse schedule(int weekday, String start, String end) {
        return SubscriptionScheduleResponse.builder().weekday(weekday).startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end)).build();
    }
}
