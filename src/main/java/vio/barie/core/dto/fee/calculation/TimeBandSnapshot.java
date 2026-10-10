package vio.barie.core.dto.fee.calculation;

import vio.barie.core.dto.fee.FeeTimeBandResponse;

import java.time.LocalTime;

/** Prices and clock values are copied, not resolved from an ID after settlement. */
public record TimeBandSnapshot(Long id, String name, Integer sortOrder,
                               LocalTime startTime, LocalTime endTime,
                               Integer firstBlockMinutes, Long firstBlockPriceVnd,
                               Integer nextBlockMinutes, Long nextBlockPriceVnd) {
    public static TimeBandSnapshot from(FeeTimeBandResponse source) {
        return new TimeBandSnapshot(source.getId(), source.getName(), source.getSortOrder(),
                source.getStartTime(), source.getEndTime(), source.getFirstBlockMinutes(),
                source.getFirstBlockPriceVnd(), source.getNextBlockMinutes(), source.getNextBlockPriceVnd());
    }
}
