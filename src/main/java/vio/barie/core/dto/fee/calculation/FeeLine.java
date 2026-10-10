package vio.barie.core.dto.fee.calculation;

import vio.barie.core.common.FeeCalculationMethod;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** An unrounded charge, retaining exact segments, copied rates and block evidence. */
public record FeeLine(FeeCalculationMethod method, TariffSnapshot tariff, TimeBandSnapshot band,
                      List<TimeRange> ranges, LocalDate localDate, Duration duration,
                      long firstBlocks, long nextBlocks, Duration remainder,
                      boolean remainderCharged, long subtotalVnd, String chargingRule) {
    public FeeLine { ranges = List.copyOf(ranges); }
}
