package vio.barie.core.dto.subscription;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

/** Only these commercial availability fields may change after publication. */
@Data
public class SubscriptionPackageSaleRequest {
    @NotNull
    private Boolean saleEnabled;
    /** Null means no end to sale availability; it does not alter any purchased period. */
    private Instant effectiveTo;
}
