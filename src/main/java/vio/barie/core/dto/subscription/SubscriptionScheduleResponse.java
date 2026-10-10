package vio.barie.core.dto.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionScheduleResponse {
    /** Start day of the weekly interval: Monday = 1, ..., Sunday = 7. */
    private Integer weekday;
    private LocalTime startTime;
    /** Earlier than startTime crosses midnight; equal to startTime means a 24-hour interval. */
    private LocalTime endTime;
}
