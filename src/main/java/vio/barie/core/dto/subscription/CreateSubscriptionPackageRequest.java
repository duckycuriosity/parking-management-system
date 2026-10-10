package vio.barie.core.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.utils.VehicleType;

/** Creates a DRAFT; backend allocates versionNo under the family's JPA lock. */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreateSubscriptionPackageRequest extends SubscriptionPackageTermsRequest {
    @NotBlank @Size(max = 100)
    private String packageCode;
    @NotNull @Positive
    private Long parkingLotId;
    @NotNull
    private VehicleType vehicleType;
}
