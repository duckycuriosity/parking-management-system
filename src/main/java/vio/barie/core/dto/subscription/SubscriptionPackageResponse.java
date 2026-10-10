package vio.barie.core.dto.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPackageResponse {
    private Long id;
    private String packageCode;
    private String name;
    private Integer versionNo;
    private Long parkingLotId;
    private VehicleType vehicleType;
    private Long priceVnd;
    private Integer durationDays;
    private SubscriptionCoverageMode coverageMode;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private boolean saleEnabled;
    private SubscriptionPackageStatus status;
    @Builder.Default
    private List<SubscriptionScheduleResponse> schedules = new ArrayList<>();
}
