package vio.barie.core.dto.subscription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.utils.VehicleType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class SubscriptionRequests {
    private SubscriptionRequests() {
    }

    @Data
    public static class SubscriptionPackageTermsRequest {
        @NotBlank @Size(max = 255)
        private String name;
        @NotNull @PositiveOrZero
        private BigDecimal priceVnd;
        @NotNull @Positive
        private Integer durationDays;
        @NotBlank
        private String coverageMode;
        @NotNull
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
        @NotNull
        private Boolean saleEnabled;
        @NotNull @Valid
        private List<SubscriptionScheduleRequest> schedules;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class CreateSubscriptionPackageRequest extends SubscriptionPackageTermsRequest {
        @NotBlank @Size(max = 100)
        private String packageCode;
        @NotNull @Positive
        private Long parkingLotId;
        @NotNull
        private VehicleType vehicleType;
    }

    @Data
    public static class SubscriptionPackageSaleRequest {
        @NotNull
        private Boolean saleEnabled;
        private LocalDateTime effectiveTo;
    }

    @Data
    public static class SubscriptionScheduleRequest {
        @NotNull @Min(1) @Max(7)
        private Integer weekday;
        @NotNull
        private LocalTime startTime;
        @NotNull
        private LocalTime endTime;
    }
}
