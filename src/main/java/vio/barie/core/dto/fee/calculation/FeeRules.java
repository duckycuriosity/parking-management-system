package vio.barie.core.dto.fee.calculation;

/**
 * Explicit approved conventions, never inferred from a tariff's reserved JSON.
 * A null field is allowed until that choice changes the applicable charging rule.
 */
public record FeeRules(BlockGrouping blockGrouping, FirstBlockRule firstBlockRule,
                       DailyGrouping dailyGrouping, DailyPriceSelection dailyPriceSelection) {
    /** Reset per contiguous occurrence, or aggregate durations per policy + band. */
    public enum BlockGrouping { CONTIGUOUS_SEGMENT, POLICY_AND_BAND }
    /** For a positive duration shorter than the first block. */
    public enum FirstBlockRule { ALWAYS_CHARGE, APPLY_THRESHOLD }
    /** One charge group per session/day, or per contiguous uncovered segment/day. */
    public enum DailyGrouping { SESSION_DAY, SEGMENT_DAY }
    /** Select first/highest daily rate, or charge once for each distinct version. */
    public enum DailyPriceSelection { FIRST, HIGHEST, EACH_VERSION }
}
