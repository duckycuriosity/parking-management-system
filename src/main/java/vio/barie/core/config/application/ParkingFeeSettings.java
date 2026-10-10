package vio.barie.core.config.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.calculation.FeeRules;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** Approved deployment conventions. No financial or timestamp convention is guessed. */
@Data
@Component
@ConfigurationProperties(prefix = "app.parking-fee")
public class ParkingFeeSettings {
    private String sessionTimeZone;
    private Map<Long, String> parkingLotTimeZones = new HashMap<>();
    private OutsidePricing outsidePricing;
    private EntitlementTiming entitlementTiming;
    private FeeRules.BlockGrouping blockGrouping;
    private FeeRules.FirstBlockRule firstBlockRule;
    private FeeRules.DailyGrouping dailyGrouping;
    private FeeRules.DailyPriceSelection dailyPriceSelection;
    private Boolean allowZeroDurationSettlement;

    public enum OutsidePricing { ENTRY_VERSION, EFFECTIVE_AT_TIME }
    public enum EntitlementTiming { PERIOD_START, PAYMENT_TIME }

    public ZoneId sessionZone() {
        return zone(sessionTimeZone, "app.parking-fee.session-time-zone");
    }

    public ZoneId lotZone(Long lotId) {
        return zone(parkingLotTimeZones.get(lotId), "parking-lot-time-zones[" + lotId + "]");
    }

    public FeeRules rules() {
        return new FeeRules(blockGrouping, firstBlockRule, dailyGrouping, dailyPriceSelection);
    }

    private static ZoneId zone(String value, String name) {
        if (value == null || value.isBlank())
            throw new BusinessException("Cần xác nhận múi giờ: " + name, "FEE_TIMEZONE_REQUIRED", 409);
        try { return ZoneId.of(value); }
        catch (RuntimeException exception) {
            throw new BusinessException("Múi giờ không hợp lệ: " + name, "FEE_TIMEZONE_INVALID", 400);
        }
    }
}
