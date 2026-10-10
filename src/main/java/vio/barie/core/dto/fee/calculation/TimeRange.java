package vio.barie.core.dto.fee.calculation;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Half-open [start, end) interval. An equal endpoint is an empty interval. */
public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.isAfter(end)) throw new IllegalArgumentException("Require start <= end");
    }

    @JsonIgnore
    public boolean isEmpty() { return start.equals(end); }
    public Duration duration() { return Duration.between(start, end); }

    public TimeRange intersection(TimeRange other) {
        Instant from = start.isAfter(other.start) ? start : other.start;
        Instant until = end.isBefore(other.end) ? end : other.end;
        return from.isBefore(until) ? new TimeRange(from, until) : null;
    }
}
