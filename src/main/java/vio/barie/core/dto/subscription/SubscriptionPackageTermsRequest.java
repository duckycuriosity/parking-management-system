package vio.barie.core.dto.subscription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import vio.barie.core.common.SubscriptionCoverageMode;

import java.time.Instant;
import java.util.List;

/** Explicit draft terms; scope and version identity cannot be changed through this request. */
@Data
public class SubscriptionPackageTermsRequest {
    @NotBlank @Size(max = 255)
    private String name;
    @NotNull @PositiveOrZero
    private Long priceVnd;
    @NotNull @Positive
    private Integer durationDays;
    @NotNull
    private SubscriptionCoverageMode coverageMode;
    @NotNull
    private Instant effectiveFrom;
    private Instant effectiveTo;
    @NotNull
    private Boolean saleEnabled;
    @NotNull @Valid
    private List<SubscriptionScheduleRequest> schedules;
}
