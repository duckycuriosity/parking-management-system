package vio.barie.core.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

/** Renewal always starts at the previous period's end; the caller cannot override its price or start. */
@Data
public class RenewSubscriptionRequest {
    @NotNull @Positive
    private Long previousSubscriptionId;
    @NotNull @Positive
    private Long packageId;
    @NotBlank @Size(max = 100)
    private String requestKey;
    @NotNull
    private Instant paymentDeadlineAt;
}
