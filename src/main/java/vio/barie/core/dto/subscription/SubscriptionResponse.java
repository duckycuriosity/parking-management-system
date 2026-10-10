package vio.barie.core.dto.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPeriodStatus;
import vio.barie.core.common.SubscriptionRegistrationType;
import vio.barie.core.common.VehicleSubscriptionStatus;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionResponse {
    private Long id;
    private Long vehicleId;
    private Long parkingLotId;
    private Long packageId;
    private String packageName;
    private String packageCode;
    private Integer packageVersionNo;
    private VehicleType vehicleType;
    private SubscriptionRegistrationType registrationType;
    private Long previousSubscriptionId;
    private Long priceVnd;
    private Instant startsAt;
    private Instant endsAt;
    private Instant paymentDeadlineAt;
    private VehicleSubscriptionStatus status;
    private Instant paidAt;
    /** Receipt that granted this period; additional transfers remain separate audit entries. */
    private Long paidReceiptId;
    /** Computed using the server clock, independently from the weekly coverage schedule. */
    private SubscriptionPeriodStatus periodStatus;
    private SubscriptionCoverageMode coverageMode;
    private Integer durationDays;
    @Builder.Default
    private List<SubscriptionScheduleResponse> schedules = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
}
