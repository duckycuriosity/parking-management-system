package vio.barie.core.service;

import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.calculation.PricingAllocation;
import vio.barie.core.dto.fee.calculation.TimeRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared, DB-free half-open interval and exact arithmetic operations. */
final class FeeCalculationSupport {
    private FeeCalculationSupport() { }
    static final Comparator<TimeRange> RANGE_ORDER = Comparator.comparing(TimeRange::start)
            .thenComparing(TimeRange::end);

    static List<TimeRange> union(List<TimeRange> input) {
        List<TimeRange> sorted = input.stream().filter(r -> !r.isEmpty()).sorted(RANGE_ORDER).toList();
        List<TimeRange> result = new ArrayList<>();
        for (TimeRange range : sorted) {
            if (result.isEmpty() || result.getLast().end().isBefore(range.start())) {
                result.add(range);
            } else {
                TimeRange previous = result.removeLast();
                result.add(new TimeRange(previous.start(), previous.end().isAfter(range.end())
                        ? previous.end() : range.end()));
            }
        }
        return List.copyOf(result);
    }

    static List<PricingAllocation> checkedAllocations(List<PricingAllocation> input) {
        if (input == null || input.stream().anyMatch(a -> a == null)) {
            throw invalid("Pricing allocations are required");
        }
        List<PricingAllocation> sorted = input.stream().filter(a -> !a.range().isEmpty())
                .sorted(Comparator.comparing(PricingAllocation::range, RANGE_ORDER)).toList();
        Instant until = null;
        for (PricingAllocation allocation : sorted) {
            if (until != null && allocation.range().start().isBefore(until)) {
                throw invalid("Pricing allocations overlap; charging the same instant twice is invalid");
            }
            until = allocation.range().end();
        }
        return sorted;
    }

    static Instant clockInstant(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime local = date.atTime(time);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) {
            throw new BusinessException("Local clock " + local + " in " + zone
                    + " is missing or ambiguous; a timezone transition convention is required",
                    "FEE_TIME_DECISION_REQUIRED", 409);
        }
        return local.toInstant(offsets.getFirst());
    }

    static void zoneRequired(ZoneId zone) {
        if (zone == null) throw invalid("An explicit parking timezone is required");
    }

    static long add(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException failure) { throw overflow(); }
    }
    static long multiply(long a, long b) {
        try { return Math.multiplyExact(a, b); }
        catch (ArithmeticException failure) { throw overflow(); }
    }
    static BusinessException overflow() {
        return new BusinessException("Calculated VND amount exceeds the supported BIGINT range",
                "FEE_AMOUNT_OVERFLOW", 400);
    }
    static BusinessException invalid(String message) {
        return new BusinessException(message, "FEE_INPUT_INVALID", 400);
    }
    static BusinessException decision(String message) {
        return new BusinessException(message, "FEE_RULE_DECISION_REQUIRED", 409);
    }
}
