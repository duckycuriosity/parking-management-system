package vio.barie.core.dto.fee;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Complete tariff configuration submitted when the operator chooses Apply. */
@Data
public class FeePolicyApplyRequest {

    @NotNull
    @Positive
    private Long parkingLotId;

    @NotNull
    private VehicleType vehicleType;

    @NotBlank
    @Size(max = 255)
    private String name;

    /** Null means apply at the server time captured by the service. */
    private LocalDateTime effectiveFrom;

    @NotNull
    private FeeCalculationMethod calculationMethod;

    @Min(0)
    private Long dailyPriceVnd;

    @Min(0)
    private Integer remainderThresholdMinutes;

    @Valid
    private List<@NotNull FeeTimeBandRequest> timeBands = new ArrayList<>();
}
