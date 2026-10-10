package vio.barie.core.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

/** The client chooses dates explicitly; price and package rights come from the published package. */
@Data
public class RegisterSubscriptionRequest {
    @NotNull @Positive
    private Long vehicleId;
    @NotNull @Positive
    private Long parkingLotId;
    @NotNull @Positive
    private Long packageId;
    @NotBlank @Size(max = 100)
    private String requestKey;
    @NotNull
    private Instant startsAt;
    @NotNull
    private Instant paymentDeadlineAt;
}
