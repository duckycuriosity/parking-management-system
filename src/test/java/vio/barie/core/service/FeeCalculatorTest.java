package vio.barie.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.dto.fee.calculation.FeeResult;
import vio.barie.core.dto.fee.calculation.TimeRange;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vio.barie.core.service.FeeCalculatorTestSupport.*;

class FeeCalculatorTest {
    private final FeeCalculator calculator = new FeeCalculator(new TimeBlockCalculator(), new DailyEntryCalculator());

    @ParameterizedTest
    @CsvSource({"23400, 24000, 600", "23000, 23000, 0", "0, 0, 0", "1, 1000, 999"})
    void roundsTheFinalTotalUpToOneThousandExactlyOnce(long raw, long expected, long adjustment) {
        FeeResult result = calculator.calculate(List.of(allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), daily(1, raw))), ZONE, null);
        assertThat(result.subtotalVnd()).isEqualTo(raw);
        assertThat(result.roundingAdjustmentVnd()).isEqualTo(adjustment);
        assertThat(result.totalVnd()).isEqualTo(expected);
    }

    @Test void mixedComponentMethodsAreAddedBeforeAnyMoneyRounding() {
        var allocations = List.of(allocation(range("2026-10-05T07:00:00", "2026-10-05T08:00:00"), daily(1, 270)),
                allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"),
                        block(2, 10, band(1, "00:00", "00:00", 60, 330, 60, 330))));
        FeeResult result = calculator.calculate(allocations, ZONE, null);
        assertThat(result.lines()).hasSize(2);
        assertThat(result.subtotalVnd()).isEqualTo(600);
        assertThat(result.totalVnd()).isEqualTo(1000);
        assertThat(result.roundingAdjustmentVnd()).isEqualTo(400);
    }

    @Test void copiedSnapshotKeepsTheActualPricesAfterSourceDtoChanges() {
        FeePolicyResponse rate = block(1, 10, band(1, "00:00", "00:00", 60, 23400, 60, 10000));
        FeeResult result = calculator.calculate(List.of(allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), rate)), ZONE, null);
        rate.getTimeBands().getFirst().setFirstBlockPriceVnd(99999L);
        rate.setName("Changed later");
        assertThat(result.lines().getFirst().tariff().timeBands().getFirst().firstBlockPriceVnd()).isEqualTo(23400);
        assertThat(result.lines().getFirst().band().firstBlockPriceVnd()).isEqualTo(23400);
        assertThat(result.lines().getFirst().tariff().name()).isEqualTo("Block 1");
        assertThatThrownBy(() -> result.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void snapshotRoundTripPreservesDurationsClockValuesAndHalfOpenIntervals() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        FeeResult original = calculator.calculate(List.of(allocation(
                range("2026-10-05T08:00:00", "2026-10-05T10:09:59.999999"), standardBlock(10))), ZONE, null);
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("PT2H9M59.999999S").doesNotContain("\"empty\"");
        FeeResult restored = mapper.readValue(json, FeeResult.class);
        assertThat(restored).isEqualTo(original);
    }

    @Test void visitorUsesTheProvidedEntryVersionEvenAfterItsEffectiveEnd() {
        FeePolicyResponse old = daily(1, 5000);
        old.setEffectiveTo(instant("2026-10-02T00:00:00"));
        FeeResult result = calculator.calculate(List.of(allocation(range("2026-10-01T20:00:00", "2026-10-03T08:00:00"), old)), ZONE, null);
        assertThat(result.lines()).hasSize(3);
        assertThat(result.totalVnd()).isEqualTo(15000);
        assertThat(result.lines()).allSatisfy(line -> assertThat(line.tariff().id()).isEqualTo(1));
    }

    @Test void identicalPreparedInputsHaveTheSameResultIndependentOfInputOrder() {
        var first = allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), daily(1, 5000));
        var second = allocation(range("2026-10-06T08:00:00", "2026-10-06T09:00:00"), daily(1, 5000));
        assertThat(calculator.calculate(List.of(first, second), ZONE, null))
                .isEqualTo(calculator.calculate(List.of(second, first), ZONE, null));
    }

    @Test void missingAndConflictingTariffDataDoNotProduceAFreeResult() {
        FeePolicyResponse missing = daily(1, 5000);
        missing.setCalculationMethod(null);
        TimeRange range = range("2026-10-05T08:00:00", "2026-10-05T09:00:00");
        assertThatThrownBy(() -> calculator.calculate(List.of(allocation(range, missing)), ZONE, null))
                .isInstanceOf(BusinessException.class);
        var conflicts = List.of(allocation(range, daily(1, 5000)),
                allocation(range("2026-10-06T08:00:00", "2026-10-06T09:00:00"), daily(1, 7000)));
        assertThatThrownBy(() -> calculator.calculate(conflicts, ZONE, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("inconsistent configuration");
    }

    @Test void emptyPricingHasZeroResultWithoutCreatingAnyChargeLine() {
        FeeResult empty = calculator.calculate(List.of(), ZONE, null);
        assertThat(empty.lines()).isEmpty();
        assertThat(empty.totalVnd()).isZero();
        Instant now = instant("2026-10-05T08:00:00");
        assertThat(calculator.calculate(List.of(allocation(new TimeRange(now, now), daily(1, 5000))), ZONE, null)).isEqualTo(empty);
    }

    @Test void bothAggregationAndFinalRoundingDetectOverflow() {
        var moreThanLong = List.of(allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"), daily(1, Long.MAX_VALUE)),
                allocation(range("2026-10-06T08:00:00", "2026-10-06T09:00:00"), daily(1, Long.MAX_VALUE)));
        assertThatThrownBy(() -> calculator.calculate(moreThanLong, ZONE, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("BIGINT range");
        assertThatThrownBy(() -> calculator.calculate(List.of(allocation(range("2026-10-05T08:00:00", "2026-10-05T09:00:00"),
                daily(1, Long.MAX_VALUE))), ZONE, null)).isInstanceOf(BusinessException.class).hasMessageContaining("BIGINT range");
    }
}
