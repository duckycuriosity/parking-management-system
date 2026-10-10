package vio.barie.core.dto.fee.calculation;

import java.util.List;

public record CoverageResult(List<CoverageWindow> sources, List<TimeRange> covered,
                             List<TimeRange> uncovered) {
    public CoverageResult {
        sources = List.copyOf(sources);
        covered = List.copyOf(covered);
        uncovered = List.copyOf(uncovered);
    }
}
