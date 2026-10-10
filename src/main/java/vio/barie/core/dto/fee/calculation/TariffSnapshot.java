package vio.barie.core.dto.fee.calculation;

import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.List;

/** Immutable copy of all rate configuration actually available to the calculator. */
public record TariffSnapshot(Integer id, Long parkingLotId, VehicleType vehicleType,
                             String name, Integer versionNo, Instant effectiveFrom, Instant effectiveTo,
                             FeeCalculationMethod calculationMethod, Long dailyPriceVnd,
                             Integer remainderThresholdMinutes, List<TimeBandSnapshot> timeBands) {
    public TariffSnapshot { timeBands = List.copyOf(timeBands); }

    public static TariffSnapshot from(FeePolicyResponse source) {
        return new TariffSnapshot(source.getId(), source.getParkingLotId(), source.getVehicleType(),
                source.getName(), source.getVersionNo(), source.getEffectiveFrom(), source.getEffectiveTo(),
                source.getCalculationMethod(), source.getDailyPriceVnd(), source.getRemainderThresholdMinutes(),
                source.getTimeBands() == null ? List.of()
                        : source.getTimeBands().stream().map(TimeBandSnapshot::from).toList());
    }
}
