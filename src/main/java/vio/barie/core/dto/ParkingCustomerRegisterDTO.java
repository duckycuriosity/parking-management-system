package vio.barie.core.dto;

import lombok.Data;
import lombok.NonNull;

@Data
public class ParkingCustomerRegisterDTO {
    @NonNull
    private String aiIdentityId;
    private String zaloId;
    @NonNull
    private String phoneNumber;
    @NonNull
    private String plateNumber;
    @NonNull
    private String role; // OWNER
    private ParkingCustomerRegisterDTO shared; // SHARED
}