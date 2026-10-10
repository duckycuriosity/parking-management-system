package vio.barie.core.dto.fee;

import vio.barie.core.config.application.ParkingFeeSettings;
import vio.barie.core.dto.fee.calculation.*;
import vio.barie.core.utils.VehicleType;
import java.time.LocalDateTime;
import java.util.List;

/** Values actually used by one engine run, not pointers to today's mutable catalog. */
public record FeeSnapshot(String engineRevision, Long sessionId, Long vehicleId, Long parkingLotId,
        VehicleType vehicleType, LocalDateTime entryTime, LocalDateTime exitTime,
        TimeRange interval, String timezone, String sessionTimeZone, String chargeContext,
        CoverageResult coverage, List<PricingAllocation> allocations, FeeRules rules,
        ParkingFeeSettings.OutsidePricing outsidePricing,
        ParkingFeeSettings.EntitlementTiming entitlementTiming, FeeResult result) {
    public FeeSnapshot { allocations = List.copyOf(allocations); }
}
