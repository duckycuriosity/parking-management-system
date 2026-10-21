package vio.barie.core.dto.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.utils.VehicleType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class SubscriptionResponses {
    private SubscriptionResponses() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionScheduleResponse {
        private Integer weekday;
        private LocalTime startTime;
        private LocalTime endTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionPackageResponse {
        private Long id;
        private String packageCode;
        private String name;
        private Integer versionNo;
        private Long parkingLotId;
        private VehicleType vehicleType;
        private BigDecimal priceVnd;
        private Integer durationDays;
        private String coverageMode;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
        private boolean saleEnabled;
        private String status;
        @Builder.Default
        private List<SubscriptionScheduleResponse> schedules = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VehicleSubscriptionResponse {
        private Long subscriptionId;
        private String plateNumber;
        private String subscriberName;
        private String parkingLotName;
        private LocalDateTime expirationDate;
        private String ticketType;
        private String status;
    }
}
