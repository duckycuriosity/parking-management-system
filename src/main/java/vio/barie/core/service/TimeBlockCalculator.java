package vio.barie.core.service;

import org.springframework.stereotype.Component;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.dto.fee.calculation.FeeLine;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.fee.calculation.PricingAllocation;
import vio.barie.core.dto.fee.calculation.TariffSnapshot;
import vio.barie.core.dto.fee.calculation.TimeBandSnapshot;
import vio.barie.core.dto.fee.calculation.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure TIME_BLOCK calculation. Rate changes never silently reset an initial block. */
@Component
public class TimeBlockCalculator {

    private static final long DAY_NANOS = Duration.ofDays(1).toNanos();

    public List<FeeLine> calculate(List<PricingAllocation> allocations, ZoneId zone, FeeRules rules) {
        FeeCalculationSupport.zoneRequired(zone);
        List<Fragment> fragments = new ArrayList<>();
        for (PricingAllocation allocation : FeeCalculationSupport.checkedAllocations(allocations)) {
            TariffSnapshot tariff = TariffSnapshot.from(allocation.policy());
            validate(tariff);
            List<Fragment> split = split(allocation.range(), tariff, zone);
            requirePartition(allocation.range(), split);
            fragments.addAll(split);
        }
        fragments.sort(Comparator.comparing(Fragment::range, FeeCalculationSupport.RANGE_ORDER));
        List<Fragment> contiguous = mergeAdjacent(fragments);
        if (contiguous.isEmpty()) return List.of();
        FeeRules.BlockGrouping grouping = rules == null ? null : rules.blockGrouping();
        if (contiguous.size() > 1 && grouping == null) {
            throw FeeCalculationSupport.decision("TIME_BLOCK crosses a rate window/version or an uncovered gap; "
                    + "approve CONTIGUOUS_SEGMENT or POLICY_AND_BAND grouping before charging");
        }
        List<List<Fragment>> groups;
        if (grouping == FeeRules.BlockGrouping.POLICY_AND_BAND) {
            Map<GroupKey, List<Fragment>> grouped = new LinkedHashMap<>();
            for (Fragment fragment : contiguous) {
                grouped.computeIfAbsent(new GroupKey(fragment.tariff(), fragment.band()), ignored -> new ArrayList<>())
                        .add(fragment);
            }
            groups = List.copyOf(grouped.values());
        } else {
            groups = contiguous.stream().map(List::of).toList();
        }
        List<FeeLine> result = new ArrayList<>();
        for (List<Fragment> group : groups) result.add(charge(group, grouping, rules));
        result.sort(Comparator.comparing(line -> line.ranges().getFirst().start()));
        return List.copyOf(result);
    }

    private FeeLine charge(List<Fragment> group, FeeRules.BlockGrouping grouping, FeeRules rules) {
        Fragment first = group.getFirst();
        List<TimeRange> ranges = group.stream().map(Fragment::range).toList();
        Duration duration = Duration.ZERO;
        for (TimeRange range : ranges) duration = duration.plus(range.duration());
        TimeBandSnapshot band = first.band();
        Duration initial = Duration.ofMinutes(band.firstBlockMinutes());
        Duration next = Duration.ofMinutes(band.nextBlockMinutes());
        Duration threshold = Duration.ofMinutes(first.tariff().remainderThresholdMinutes());
        long firstBlocks = 1;
        long nextBlocks = 0;
        Duration remainder;
        boolean remainderCharged;
        FeeRules.FirstBlockRule shortRule = rules == null ? null : rules.firstBlockRule();
        if (duration.compareTo(initial) < 0) {
            if (shortRule == null) {
                throw FeeCalculationSupport.decision("Positive duration " + duration
                        + " is shorter than initial block " + initial
                        + "; approve ALWAYS_CHARGE or APPLY_THRESHOLD for the initial block");
            }
            remainder = duration;
            remainderCharged = shortRule == FeeRules.FirstBlockRule.ALWAYS_CHARGE
                    || duration.compareTo(threshold) >= 0;
            firstBlocks = remainderCharged ? 1 : 0;
        } else {
            Duration remaining = duration.minus(initial);
            nextBlocks = remaining.dividedBy(next);
            remainder = remaining.minus(next.multipliedBy(nextBlocks));
            // Threshold zero means any POSITIVE remainder, not a new block at an exact boundary.
            remainderCharged = !remainder.isZero() && remainder.compareTo(threshold) >= 0;
            if (remainderCharged) nextBlocks = FeeCalculationSupport.add(nextBlocks, 1);
        }
        long subtotal = FeeCalculationSupport.add(
                FeeCalculationSupport.multiply(firstBlocks, band.firstBlockPriceVnd()),
                FeeCalculationSupport.multiply(nextBlocks, band.nextBlockPriceVnd()));
        String convention = grouping == null ? "SINGLE_CONTIGUOUS_BAND" : grouping.name();
        if (duration.compareTo(initial) < 0) convention += "/" + shortRule.name();
        return new FeeLine(FeeCalculationMethod.TIME_BLOCK, first.tariff(), band, ranges, null,
                duration, firstBlocks, nextBlocks, remainder, remainderCharged, subtotal, convention);
    }

    private List<Fragment> split(TimeRange range, TariffSnapshot tariff, ZoneId zone) {
        List<Fragment> result = new ArrayList<>();
        LocalDate first = range.start().atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = range.end().atZone(zone).toLocalDate();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            for (TimeBandSnapshot band : tariff.timeBands()) {
                LocalDate endDay = band.endTime().isAfter(band.startTime()) ? day : day.plusDays(1);
                Instant start = FeeCalculationSupport.clockInstant(day, band.startTime(), zone);
                Instant end = FeeCalculationSupport.clockInstant(endDay, band.endTime(), zone);
                if (!start.isBefore(end)) throw FeeCalculationSupport.invalid("Time band has no positive elapsed duration");
                TimeRange clipped = range.intersection(new TimeRange(start, end));
                if (clipped != null) result.add(new Fragment(clipped, tariff, band));
            }
        }
        result.sort(Comparator.comparing(Fragment::range, FeeCalculationSupport.RANGE_ORDER));
        return result;
    }

    private List<Fragment> mergeAdjacent(List<Fragment> input) {
        List<Fragment> result = new ArrayList<>();
        for (Fragment fragment : input) {
            if (!result.isEmpty()) {
                Fragment previous = result.getLast();
                if (previous.range().end().equals(fragment.range().start())
                        && previous.tariff().equals(fragment.tariff()) && previous.band().equals(fragment.band())) {
                    result.set(result.size() - 1, new Fragment(new TimeRange(previous.range().start(), fragment.range().end()),
                            previous.tariff(), previous.band()));
                    continue;
                }
            }
            result.add(fragment);
        }
        return result;
    }

    private void requirePartition(TimeRange original, List<Fragment> fragments) {
        Instant cursor = original.start();
        for (Fragment fragment : fragments) {
            if (!fragment.range().start().equals(cursor)) {
                throw FeeCalculationSupport.invalid("Time-band windows leave a gap or overlap in the priced interval");
            }
            cursor = fragment.range().end();
        }
        if (!cursor.equals(original.end())) throw FeeCalculationSupport.invalid("Time bands do not cover the priced interval");
    }

    private void validate(TariffSnapshot tariff) {
        if (tariff.calculationMethod() != FeeCalculationMethod.TIME_BLOCK
                || tariff.remainderThresholdMinutes() == null || tariff.remainderThresholdMinutes() < 0
                || tariff.timeBands().isEmpty() || tariff.dailyPriceVnd() != null) {
            throw FeeCalculationSupport.invalid("TIME_BLOCK requires bands and a nonnegative remainder threshold");
        }
        List<ClockRange> clockRanges = new ArrayList<>();
        for (TimeBandSnapshot band : tariff.timeBands()) {
            if (band.startTime() == null || band.endTime() == null || band.firstBlockMinutes() == null
                    || band.firstBlockMinutes() <= 0 || band.nextBlockMinutes() == null || band.nextBlockMinutes() <= 0
                    || band.firstBlockPriceVnd() == null || band.firstBlockPriceVnd() < 0
                    || band.nextBlockPriceVnd() == null || band.nextBlockPriceVnd() < 0) {
                throw FeeCalculationSupport.invalid("Time bands require clock endpoints, positive block lengths and nonnegative prices");
            }
            long start = band.startTime().toNanoOfDay();
            long end = band.endTime().toNanoOfDay();
            if (start == end) clockRanges.add(new ClockRange(0, DAY_NANOS));
            else if (start < end) clockRanges.add(new ClockRange(start, end));
            else {
                clockRanges.add(new ClockRange(start, DAY_NANOS));
                if (end > 0) clockRanges.add(new ClockRange(0, end));
            }
        }
        clockRanges.sort(Comparator.comparingLong(ClockRange::start));
        long cursor = 0;
        for (ClockRange range : clockRanges) {
            if (range.start() != cursor) throw FeeCalculationSupport.invalid("Time bands must partition all 24 local hours without gap or overlap");
            cursor = range.end();
        }
        if (cursor != DAY_NANOS) throw FeeCalculationSupport.invalid("Time bands must cover all 24 local hours");
    }

    private record Fragment(TimeRange range, TariffSnapshot tariff, TimeBandSnapshot band) { }
    private record GroupKey(TariffSnapshot tariff, TimeBandSnapshot band) { }
    private record ClockRange(long start, long end) { }
}
