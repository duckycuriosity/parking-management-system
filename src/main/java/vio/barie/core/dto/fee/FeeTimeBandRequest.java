package vio.barie.core.dto.fee;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

/** Prices and local recurring hours belonging to one tariff version. */
@Data
public class FeeTimeBandRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Min(0)
    private Integer sortOrder = 0;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    @NotNull
    @Positive
    private Integer firstBlockMinutes;

    @NotNull
    @Min(0)
    private Long firstBlockPriceVnd;

    @NotNull
    @Positive
    private Integer nextBlockMinutes;

    @NotNull
    @Min(0)
    private Long nextBlockPriceVnd;
}
