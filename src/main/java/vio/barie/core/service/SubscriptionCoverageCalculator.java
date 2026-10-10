package vio.barie.core.service;

import org.springframework.stereotype.Component;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.dto.fee.calculation.CoverageResult;
import vio.barie.core.dto.fee.calculation.CoverageWindow;
import vio.barie.core.dto.fee.calculation.TimeRange;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.dto.subscription.SubscriptionScheduleResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure intersection/union/complement; it neither selects prices nor reads persistence. */
@Component
public class SubscriptionCoverageCalculator {

    public CoverageResult calculate(TimeRange session, List<SubscriptionResponse> periods, ZoneId zone) {
        FeeCalculationSupport.zoneRequired(zone);
        if (session == null || periods == null || periods.stream().anyMatch(p -> p == null)) {
            throw FeeCalculationSupport.invalid("Session interval and supplied subscription periods are required");
        }
        if (session.isEmpty()) return new CoverageResult(List.of(), List.of(), List.of());
        List<CoverageWindow> sources = new ArrayList<>();
        for (SubscriptionResponse period : periods) {
            if (period.getStatus() != VehicleSubscriptionStatus.PAID) continue;
            if (period.getStartsAt() == null || period.getEndsAt() == null
                    || !period.getStartsAt().isBefore(period.getEndsAt()) || period.getCoverageMode() == null) {
                throw FeeCalculationSupport.invalid("Paid subscription has an invalid period or coverage mode");
            }
            TimeRange eligible = session.intersection(new TimeRange(period.getStartsAt(), period.getEndsAt()));
            if (eligible == null) continue;
            if (period.getCoverageMode() == SubscriptionCoverageMode.ALL_DAY) {
                sources.add(source(eligible, period, null));
            } else {
                List<SubscriptionScheduleResponse> schedules = period.getSchedules();
                if (schedules == null || schedules.isEmpty()) {
                    throw FeeCalculationSupport.invalid("Paid WEEKLY subscription has no entitlement schedule");
                }
                schedules.forEach(this::validateSchedule);
                // Include yesterday: a Sunday window may cover Monday after midnight.
                LocalDate first = eligible.start().atZone(zone).toLocalDate().minusDays(1);
                LocalDate last = eligible.end().atZone(zone).toLocalDate();
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    for (SubscriptionScheduleResponse schedule : schedules) {
                        if (schedule.getWeekday() != day.getDayOfWeek().getValue()) continue;
                        LocalDate endDay = schedule.getEndTime().isAfter(schedule.getStartTime())
                                ? day : day.plusDays(1);
                        Instant start = FeeCalculationSupport.clockInstant(day, schedule.getStartTime(), zone);
                        Instant end = FeeCalculationSupport.clockInstant(endDay, schedule.getEndTime(), zone);
                        if (!start.isBefore(end)) {
                            throw FeeCalculationSupport.invalid("Entitlement window has no positive elapsed duration");
                        }
                        TimeRange clipped = eligible.intersection(new TimeRange(start, end));
                        if (clipped != null) sources.add(source(clipped, period, schedule));
                    }
                }
            }
        }
        sources.sort(Comparator.comparing(CoverageWindow::range, FeeCalculationSupport.RANGE_ORDER)
                .thenComparing(CoverageWindow::subscriptionId, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(CoverageWindow::packageId, Comparator.nullsFirst(Long::compareTo)));
        List<TimeRange> covered = FeeCalculationSupport.union(sources.stream().map(CoverageWindow::range).toList());
        List<TimeRange> uncovered = new ArrayList<>();
        Instant cursor = session.start();
        for (TimeRange range : covered) {
            if (cursor.isBefore(range.start())) uncovered.add(new TimeRange(cursor, range.start()));
            cursor = range.end();
        }
        if (cursor.isBefore(session.end())) uncovered.add(new TimeRange(cursor, session.end()));
        return new CoverageResult(sources, covered, uncovered);
    }

    private void validateSchedule(SubscriptionScheduleResponse schedule) {
        if (schedule == null || schedule.getWeekday() == null || schedule.getWeekday() < 1
                || schedule.getWeekday() > 7 || schedule.getStartTime() == null || schedule.getEndTime() == null) {
            throw FeeCalculationSupport.invalid("Weekly entitlement requires weekday 1..7 and clock endpoints");
        }
    }

    private CoverageWindow source(TimeRange range, SubscriptionResponse period, SubscriptionScheduleResponse schedule) {
        return new CoverageWindow(range, period.getId(), period.getPackageId(), period.getPackageVersionNo(),
                period.getCoverageMode(), schedule == null ? null : schedule.getWeekday(),
                schedule == null ? null : schedule.getStartTime(), schedule == null ? null : schedule.getEndTime());
    }
}
