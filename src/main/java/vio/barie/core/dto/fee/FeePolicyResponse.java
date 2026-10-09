package vio.barie.core.dto.fee;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeePolicyResponse {

    private Integer id;
    private Long parkingLotId;
    private VehicleType vehicleType;
    private String name;
    private Integer versionNo;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private FeeCalculationMethod calculationMethod;
    private Long dailyPriceVnd;
    private Integer remainderThresholdMinutes;

    @Builder.Default
    private List<FeeTimeBandResponse> timeBands = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
}
