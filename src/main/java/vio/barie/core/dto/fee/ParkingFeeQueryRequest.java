package vio.barie.core.dto.fee;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** Display-only historical/simulation cutoff; never used to create a payable quote. */
public record ParkingFeeQueryRequest(@NotNull @Positive Long sessionId, Instant until) { }
