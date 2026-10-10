package vio.barie.core.service;

import org.springframework.stereotype.Component;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.dto.fee.calculation.FeeLine;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.fee.calculation.PricingAllocation;
import vio.barie.core.dto.fee.calculation.TariffSnapshot;
import vio.barie.core.dto.fee.calculation.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A continuous visitor session is charged by local dates, never by rounded 24h durations. */
@Component
public class DailyEntryCalculator {

    public List<FeeLine> calculate(List<PricingAllocation> allocations, ZoneId zone, FeeRules rules) {
        FeeCalculationSupport.zoneRequired(zone);
        Map<LocalDate, List<DayFragment>> days = new TreeMap<>();
        for (PricingAllocation allocation : FeeCalculationSupport.checkedAllocations(allocations)) {
            TariffSnapshot tariff = TariffSnapshot.from(allocation.policy());
            validate(tariff);
            Instant cursor = allocation.range().start();
            while (cursor.isBefore(allocation.range().end())) {
                LocalDate day = cursor.atZone(zone).toLocalDate();
                Instant nextDay = FeeCalculationSupport.clockInstant(day.plusDays(1), LocalTime.MIDNIGHT, zone);
                Instant until = nextDay.isBefore(allocation.range().end()) ? nextDay : allocation.range().end();
                if (!cursor.isBefore(until)) throw FeeCalculationSupport.invalid("Local calendar boundary does not advance time");
                days.computeIfAbsent(day, ignored -> new ArrayList<>())
                        .add(new DayFragment(new TimeRange(cursor, until), tariff));
                cursor = until;
            }
        }
        List<FeeLine> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DayFragment>> day : days.entrySet()) {
            List<DayFragment> fragments = day.getValue();
            fragments.sort(Comparator.comparing(DayFragment::range, FeeCalculationSupport.RANGE_ORDER));
            List<List<DayFragment>> runs = contiguousRuns(fragments);
            FeeRules.DailyGrouping grouping = rules == null ? null : rules.dailyGrouping();
            if (runs.size() > 1 && grouping == null) {
                throw FeeCalculationSupport.decision("DAILY_ENTRY has several uncovered segments on " + day.getKey()
                        + "; approve SESSION_DAY or SEGMENT_DAY before charging");
            }
            List<List<DayFragment>> groups = grouping == FeeRules.DailyGrouping.SEGMENT_DAY ? runs : List.of(fragments);
            for (List<DayFragment> group : groups) result.addAll(charge(day.getKey(), group, grouping, rules));
        }
        return List.copyOf(result);
    }

    private List<FeeLine> charge(LocalDate day, List<DayFragment> group,
                                 FeeRules.DailyGrouping grouping, FeeRules rules) {
        Map<Integer, TariffSnapshot> rates = new LinkedHashMap<>();
        for (DayFragment fragment : group) {
            TariffSnapshot existing = rates.putIfAbsent(fragment.tariff().id(), fragment.tariff());
            if (existing != null && !existing.equals(fragment.tariff())) {
                throw FeeCalculationSupport.invalid("The same tariff identifier has inconsistent configuration");
            }
        }
        FeeRules.DailyPriceSelection selection = rules == null ? null : rules.dailyPriceSelection();
        if (rates.size() > 1 && selection == null) {
            throw FeeCalculationSupport.decision("DAILY_ENTRY encounters different tariff versions on " + day
                    + "; approve FIRST, HIGHEST or EACH_VERSION daily price selection");
        }
        String convention = grouping == null ? "SINGLE_CONTIGUOUS_SESSION_DAY" : grouping.name();
        if (rates.size() > 1) convention += "/" + selection.name();
        if (rates.size() > 1 && selection == FeeRules.DailyPriceSelection.EACH_VERSION) {
            List<FeeLine> result = new ArrayList<>();
            for (TariffSnapshot rate : rates.values()) {
                List<TimeRange> ranges = group.stream().filter(f -> f.tariff().id().equals(rate.id()))
                        .map(DayFragment::range).toList();
                result.add(line(day, rate, ranges, convention));
            }
            return result;
        }
        TariffSnapshot chosen = rates.values().iterator().next();
        if (selection == FeeRules.DailyPriceSelection.HIGHEST) {
            // Ties keep the earliest source, so snapshot evidence remains deterministic.
            for (TariffSnapshot rate : rates.values()) {
                if (rate.dailyPriceVnd() > chosen.dailyPriceVnd()) chosen = rate;
            }
        }
        return List.of(line(day, chosen, group.stream().map(DayFragment::range).toList(), convention));
    }

    private FeeLine line(LocalDate day, TariffSnapshot rate, List<TimeRange> ranges, String convention) {
        Duration duration = Duration.ZERO;
        for (TimeRange range : ranges) duration = duration.plus(range.duration());
        return new FeeLine(FeeCalculationMethod.DAILY_ENTRY, rate, null, ranges, day, duration,
                0, 0, Duration.ZERO, false, rate.dailyPriceVnd(), convention);
    }

    private List<List<DayFragment>> contiguousRuns(List<DayFragment> fragments) {
        List<List<DayFragment>> result = new ArrayList<>();
        for (DayFragment fragment : fragments) {
            if (result.isEmpty() || !result.getLast().getLast().range().end().equals(fragment.range().start())) {
                result.add(new ArrayList<>());
            }
            result.getLast().add(fragment);
        }
        return result;
    }

    private void validate(TariffSnapshot tariff) {
        if (tariff.calculationMethod() != FeeCalculationMethod.DAILY_ENTRY
                || tariff.dailyPriceVnd() == null || tariff.dailyPriceVnd() < 0
                || tariff.remainderThresholdMinutes() != null || !tariff.timeBands().isEmpty()) {
            throw FeeCalculationSupport.invalid("DAILY_ENTRY requires a nonnegative day price and no block configuration");
        }
        if (tariff.id() == null) throw FeeCalculationSupport.invalid("A persisted tariff version identifier is required");
    }

    private record DayFragment(TimeRange range, TariffSnapshot tariff) { }
}
