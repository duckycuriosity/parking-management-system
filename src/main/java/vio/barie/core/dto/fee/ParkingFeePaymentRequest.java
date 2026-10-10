package vio.barie.core.dto.fee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ParkingFeePaymentRequest(@NotBlank @Size(max = 100) String requestKey) { }
