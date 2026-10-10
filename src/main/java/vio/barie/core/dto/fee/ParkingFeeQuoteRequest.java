package vio.barie.core.dto.fee;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** Cutoff comes from the backend; deadline is an explicit operator workflow decision. */
public record ParkingFeeQuoteRequest(@NotNull @Positive Long sessionId,
                                    @NotNull Instant paymentDeadlineAt) { }
