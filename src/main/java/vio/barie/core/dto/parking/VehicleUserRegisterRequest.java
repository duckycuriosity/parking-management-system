package vio.barie.core.dto.parking;

import lombok.Data;
import lombok.NonNull;
import jakarta.validation.constraints.NotBlank;

@Data
public class VehicleUserRegisterRequest {
    @NonNull
    private String aiIdentityId;
    private String zaloId;
    @NotBlank
    private String username;
    @NotBlank
    private String phoneNumber;
    @NotBlank
    private String plateNumber;
    private String role; // OWNER | SHARED
}