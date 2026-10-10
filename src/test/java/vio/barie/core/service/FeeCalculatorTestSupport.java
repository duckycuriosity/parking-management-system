package vio.barie.core.service;

import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.dto.fee.FeeTimeBandResponse;
import vio.barie.core.dto.fee.calculation.PricingAllocation;
import vio.barie.core.dto.fee.calculation.TimeRange;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

final class FeeCalculatorTestSupport {
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static Instant instant(String local) { return LocalDateTime.parse(local).atZone(ZONE).toInstant(); }
    static TimeRange range(String from, String until) { return new TimeRange(instant(from), instant(until)); }
    static PricingAllocation allocation(TimeRange range, FeePolicyResponse rate) { return new PricingAllocation(range, rate); }
    static FeePolicyResponse daily(int id, long price) {
        return FeePolicyResponse.builder().id(id).parkingLotId(33L).vehicleType(VehicleType.MOTORBIKE)
                .name("Daily " + id).versionNo(id).effectiveFrom(instant("2026-01-01T00:00:00"))
                .calculationMethod(FeeCalculationMethod.DAILY_ENTRY).dailyPriceVnd(price).build();
    }
    static FeeTimeBandResponse band(long id, String start, String end, int firstMinutes,
                                     long firstPrice, int nextMinutes, long nextPrice) {
        return FeeTimeBandResponse.builder().id(id).name("Band " + id).sortOrder((int) id)
                .startTime(LocalTime.parse(start)).endTime(LocalTime.parse(end))
                .firstBlockMinutes(firstMinutes).firstBlockPriceVnd(firstPrice)
                .nextBlockMinutes(nextMinutes).nextBlockPriceVnd(nextPrice).build();
    }
    static FeePolicyResponse block(int id, int threshold, FeeTimeBandResponse... bands) {
        return FeePolicyResponse.builder().id(id).parkingLotId(33L).vehicleType(VehicleType.CAR)
                .name("Block " + id).versionNo(id).effectiveFrom(instant("2026-01-01T00:00:00"))
                .calculationMethod(FeeCalculationMethod.TIME_BLOCK).remainderThresholdMinutes(threshold)
                .timeBands(List.of(bands)).build();
    }
    static FeePolicyResponse standardBlock(int threshold) {
        return block(1, threshold, band(1, "00:00", "00:00", 60, 20_000, 60, 10_000));
    }
}
