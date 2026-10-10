package vio.barie.core.dto.fee.calculation;

import java.util.List;

public record FeeResult(List<FeeLine> lines, long subtotalVnd,
                        long roundingAdjustmentVnd, long totalVnd) {
    public FeeResult { lines = List.copyOf(lines); }
}
