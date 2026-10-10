package vio.barie.core.dto.subscription;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

@Data
public class SubscriptionScheduleRequest {
    /** Starting weekday: Monday = 1 through Sunday = 7. */
    @NotNull @Min(1) @Max(7)
    private Integer weekday;
    @NotNull
    private LocalTime startTime;
    /** End before start crosses midnight; equal start/end is a 24-hour interval. */
    @NotNull
    private LocalTime endTime;
}
