package vio.barie.core.dto.fee.calculation;

import vio.barie.core.common.SubscriptionCoverageMode;

import java.time.LocalTime;

/** Source entitlement and the exact clipped window used, before union. */
public record CoverageWindow(TimeRange range, Long subscriptionId, Long packageId,
                             Integer packageVersionNo, SubscriptionCoverageMode coverageMode,
                             Integer weekday, LocalTime scheduleStartTime, LocalTime scheduleEndTime) { }
