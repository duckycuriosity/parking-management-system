package vio.barie.core.dto.fee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeeTimeBandResponse {

    private Long id;
    private String name;
    private Integer sortOrder;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer firstBlockMinutes;
    private Long firstBlockPriceVnd;
    private Integer nextBlockMinutes;
    private Long nextBlockPriceVnd;
    private Instant createdAt;
    private Instant updatedAt;
}
