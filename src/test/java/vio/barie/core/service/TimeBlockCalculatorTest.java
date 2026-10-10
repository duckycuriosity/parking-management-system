package vio.barie.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.dto.fee.calculation.FeeLine;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.fee.calculation.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vio.barie.core.service.FeeCalculatorTestSupport.*;

class TimeBlockCalculatorTest {
    private final TimeBlockCalculator calculator = new TimeBlockCalculator();
    private final Instant start = instant("2026-10-05T08:00:00");

    @ParameterizedTest
    @CsvSource({"60, 0, 0, 20000", "120, 0, 1, 30000", "129, 0, 1, 30000",
            "129, 59999999, 1, 30000", "130, 0, 2, 40000", "130, 1, 2, 40000"})
    void comparesRemaindersAtFullMicrosecondPrecision(long minutes, long micros, long next, long expected) {
        FeeLine line = single(standardBlock(10), Duration.ofMinutes(minutes).plusNanos(micros * 1000), null);
        assertThat(line.firstBlocks()).isEqualTo(1);
        assertThat(line.nextBlocks()).isEqualTo(next);
        assertThat(line.subtotalVnd()).isEqualTo(expected);
    }

    @Test void initialAndNextBlocksMayHaveDifferentLengthsAndRates() {
        FeePolicyResponse tariff = block(1, 10, band(1, "00:00", "00:00", 30, 20_000, 60, 10_000));
        FeeLine below = single(tariff, Duration.ofMinutes(159), null);
        FeeLine at = single(tariff, Duration.ofMinutes(160), null);
        assertThat(below.firstBlocks()).isEqualTo(1);
        assertThat(below.nextBlocks()).isEqualTo(2);
        assertThat(below.subtotalVnd()).isEqualTo(40_000);
        assertThat(at.nextBlocks()).isEqualTo(3);
        assertThat(at.subtotalVnd()).isEqualTo(50_000);
    }

    @Test void zeroThresholdChargesAnyPositiveRemainderButNotAnExactBoundary() {
        FeeLine exact = single(standardBlock(0), Duration.ofMinutes(120), null);
        FeeLine positive = single(standardBlock(0), Duration.ofMinutes(120).plusNanos(1000), null);
        assertThat(exact.nextBlocks()).isEqualTo(1);
        assertThat(exact.remainderCharged()).isFalse();
        assertThat(positive.nextBlocks()).isEqualTo(2);
        assertThat(positive.remainderCharged()).isTrue();
    }

    @Test void aShortInitialBlockRequiresAnExplicitApprovedConvention() {
        assertThatThrownBy(() -> single(standardBlock(10), Duration.ofMinutes(9), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("shorter than initial block");
        FeeLine minimum = single(standardBlock(10), Duration.ofMinutes(9),
                new FeeRules(null, FeeRules.FirstBlockRule.ALWAYS_CHARGE, null, null));
        FeeLine threshold = single(standardBlock(10), Duration.ofMinutes(9),
                new FeeRules(null, FeeRules.FirstBlockRule.APPLY_THRESHOLD, null, null));
        FeeLine at = single(standardBlock(10), Duration.ofMinutes(10),
                new FeeRules(null, FeeRules.FirstBlockRule.APPLY_THRESHOLD, null, null));
        assertThat(minimum.subtotalVnd()).isEqualTo(20_000);
        assertThat(threshold.subtotalVnd()).isZero();
        assertThat(at.subtotalVnd()).isEqualTo(20_000);
    }

    @Test void uncoveredGapsAreNotAutomaticallyTurnedIntoSeveralFirstBlocks() {
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), standardBlock(10)),
                allocation(range("2026-10-05T17:00:00", "2026-10-05T19:00:00"), standardBlock(10)));
        assertThatThrownBy(() -> calculator.calculate(allocations, ZONE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("approve CONTIGUOUS_SEGMENT");
        var aggregate = calculator.calculate(allocations, ZONE,
                new FeeRules(FeeRules.BlockGrouping.POLICY_AND_BAND, null, null, null));
        var separate = calculator.calculate(allocations, ZONE,
                new FeeRules(FeeRules.BlockGrouping.CONTIGUOUS_SEGMENT, null, null, null));
        assertThat(aggregate).hasSize(1);
        assertThat(aggregate.getFirst().ranges()).hasSize(2);
        assertThat(aggregate.getFirst().duration()).isEqualTo(Duration.ofHours(3));
        assertThat(aggregate.getFirst().subtotalVnd()).isEqualTo(40_000);
        assertThat(separate).hasSize(2);
        assertThat(separate.stream().mapToLong(FeeLine::subtotalVnd).sum()).isEqualTo(50_000);
    }

    @Test void contiguousAllocationSlicingDoesNotCreateAdditionalFirstBlocks() {
        FeePolicyResponse tariff = standardBlock(10);
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), tariff),
                allocation(range("2026-10-05T08:00:00", "2026-10-05T10:00:00"), tariff));
        var lines = calculator.calculate(allocations, ZONE, null);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().subtotalVnd()).isEqualTo(40_000);
    }

    @Test void switchingClockBandsRequiresGroupingApprovalAndUsesBothCorrectRates() {
        FeePolicyResponse tariff = dayNight();
        var allocation = allocation(range("2026-10-05T17:30:00", "2026-10-05T18:30:00"), tariff);
        assertThatThrownBy(() -> calculator.calculate(List.of(allocation), ZONE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("rate window");
        var lines = calculator.calculate(List.of(allocation), ZONE,
                new FeeRules(FeeRules.BlockGrouping.CONTIGUOUS_SEGMENT, FeeRules.FirstBlockRule.ALWAYS_CHARGE, null, null));
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().mapToLong(FeeLine::subtotalVnd).sum()).isEqualTo(35_000);
        assertThat(lines.getFirst().band().id()).isEqualTo(1);
        assertThat(lines.getLast().band().id()).isEqualTo(2);
    }

    @Test void anExactBandBoundaryBelongsToTheNextBandAndMidnightDoesNotSplitANightBand() {
        var atBoundary = calculator.calculate(List.of(allocation(range("2026-10-05T18:00:00", "2026-10-05T19:00:00"), dayNight())), ZONE, null);
        var crossesMidnight = calculator.calculate(List.of(allocation(range("2026-10-05T23:30:00", "2026-10-06T00:30:00"), dayNight())), ZONE, null);
        assertThat(atBoundary).hasSize(1);
        assertThat(atBoundary.getFirst().band().id()).isEqualTo(2);
        assertThat(atBoundary.getFirst().subtotalVnd()).isEqualTo(15_000);
        assertThat(crossesMidnight).hasSize(1);
        assertThat(crossesMidnight.getFirst().subtotalVnd()).isEqualTo(15_000);
    }

    @Test void aTwentyFourHourBandDoesNotResetAtItsArtificialCalendarSplit() {
        FeePolicyResponse tariff = block(1, 10, band(1, "06:00", "06:00", 60, 20_000, 60, 10_000));
        var lines = calculator.calculate(List.of(allocation(range("2026-10-05T07:00:00", "2026-10-06T07:00:00"), tariff)), ZONE, null);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().firstBlocks()).isEqualTo(1);
        assertThat(lines.getFirst().nextBlocks()).isEqualTo(23);
    }

    @Test void invalidBandCoverageFailsInsteadOfProducingAZeroFee() {
        FeePolicyResponse gap = block(1, 10, band(1, "06:00", "18:00", 60, 20_000, 60, 10_000));
        assertThatThrownBy(() -> single(gap, Duration.ofHours(1), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("all 24 local hours");
        FeePolicyResponse overlap = block(1, 10, band(1, "00:00", "00:00", 60, 20_000, 60, 10_000),
                band(2, "08:00", "09:00", 60, 20_000, 60, 10_000));
        assertThatThrownBy(() -> single(overlap, Duration.ofHours(1), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("gap or overlap");
    }

    @Test void blockPricesStayUnroundedAndAnArithmeticOverflowIsReported() {
        FeePolicyResponse raw = block(1, 10, band(1, "00:00", "00:00", 60, 234, 60, 241));
        assertThat(single(raw, Duration.ofHours(2), null).subtotalVnd()).isEqualTo(475);
        FeePolicyResponse overflow = block(1, 10, band(1, "00:00", "00:00", 60, Long.MAX_VALUE, 60, 1));
        assertThatThrownBy(() -> single(overflow, Duration.ofHours(2), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("BIGINT range");
    }

    @Test void zeroDurationIsEmptyAndNeedsNoMinimumFeeRule() {
        assertThat(calculator.calculate(List.of(allocation(new TimeRange(start, start), standardBlock(10))), ZONE, null)).isEmpty();
    }

    private FeeLine single(FeePolicyResponse tariff, Duration duration, FeeRules rules) {
        return calculator.calculate(List.of(allocation(new TimeRange(start, start.plus(duration)), tariff)), ZONE, rules).getFirst();
    }
    private FeePolicyResponse dayNight() {
        return block(1, 10, band(1, "06:00", "18:00", 60, 20_000, 60, 10_000),
                band(2, "18:00", "06:00", 60, 15_000, 60, 8_000));
    }
}
