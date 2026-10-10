package vio.barie.core.service;

import org.springframework.stereotype.Component;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.dto.fee.calculation.FeeLine;
import vio.barie.core.dto.fee.calculation.FeeResult;
import vio.barie.core.dto.fee.calculation.FeeRules;
import vio.barie.core.dto.fee.calculation.PricingAllocation;
import vio.barie.core.dto.fee.calculation.TariffSnapshot;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure dispatch and one final VND rounding operation across every component. */
@Component
public class FeeCalculator {

    private final TimeBlockCalculator timeBlocks;
    private final DailyEntryCalculator dailyEntries;

    public FeeCalculator(TimeBlockCalculator timeBlocks, DailyEntryCalculator dailyEntries) {
        this.timeBlocks = timeBlocks;
        this.dailyEntries = dailyEntries;
    }

    public FeeResult calculate(List<PricingAllocation> allocations, ZoneId zone, FeeRules rules) {
        FeeCalculationSupport.zoneRequired(zone);
        List<PricingAllocation> checked = FeeCalculationSupport.checkedAllocations(allocations);
        Map<Integer, TariffSnapshot> sourceRates = new LinkedHashMap<>();
        for (PricingAllocation allocation : checked) {
            TariffSnapshot rate = TariffSnapshot.from(allocation.policy());
            if (rate.id() == null || rate.calculationMethod() == null) {
                throw FeeCalculationSupport.invalid("A persisted tariff and calculation method are required");
            }
            TariffSnapshot existing = sourceRates.putIfAbsent(rate.id(), rate);
            if (existing != null && !existing.equals(rate)) {
                throw FeeCalculationSupport.invalid("The same tariff identifier has inconsistent configuration");
            }
        }
        List<FeeLine> lines = new ArrayList<>();
        List<PricingAllocation> blocks = checked.stream()
                .filter(a -> a.policy().getCalculationMethod() == FeeCalculationMethod.TIME_BLOCK).toList();
        List<PricingAllocation> days = checked.stream()
                .filter(a -> a.policy().getCalculationMethod() == FeeCalculationMethod.DAILY_ENTRY).toList();
        if (!blocks.isEmpty()) lines.addAll(timeBlocks.calculate(blocks, zone, rules));
        if (!days.isEmpty()) lines.addAll(dailyEntries.calculate(days, zone, rules));
        lines.sort(Comparator.comparing((FeeLine line) -> line.ranges().getFirst().start())
                .thenComparing(line -> line.tariff().id()));
        long subtotal = 0;
        for (FeeLine line : lines) subtotal = FeeCalculationSupport.add(subtotal, line.subtotalVnd());
        long adjustment = subtotal % 1000 == 0 ? 0 : 1000 - subtotal % 1000;
        return new FeeResult(lines, subtotal, adjustment, FeeCalculationSupport.add(subtotal, adjustment));
    }
}
