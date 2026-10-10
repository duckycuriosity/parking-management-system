package vio.barie.core.dto.owneraccount;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import java.time.OffsetDateTime;
import java.util.List;

public final class OwnerAccountDtos {
    private OwnerAccountDtos() {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OwnerAccountPaginationRequest {
        @Builder.Default private int page = 0;
        @Builder.Default private int size = 20;
        private String searchTerm, status, fromDate, toDate;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class OwnerAccountRequest extends OwnerProfileCommand {
        @NotBlank @Size(max = 255) private String fullName;
        @NotBlank @Size(max = 30) private String phone;
        @Size(max = 30) private String identityNumber;
        private String address, zaloLink, note;
        /** Optional explicit administration action; new records default to ACTIVE. */
        @Pattern(regexp = "ACTIVE|INACTIVE") private String status;
    }
    @Data @Builder
    public static class OwnerAccountResponse {
        private Long id, aiProfileId, driverCustomerId;
        private String fullName, phone, identityNumber, address, imageUrl, zaloLink, note, status, aiSyncState;
        private Long vehicleCount;
        private OffsetDateTime createdAt, updatedAt;
    }
    @Data @Builder
    public static class OwnerAccountVehicleParkingCustomerResponse {
        private String requestKey;
        private Long id, aiProfileId, driverCustomerId;
        private String fullName, phone, identityNumber, address, imageUrl, zaloLink, note, status, aiSyncState;
        private Long vehicleCount;
        private OffsetDateTime createdAt, updatedAt;
        private List<VehicleResponse> vehicles;
        private List<CustomerResponse> customers;
        private List<VehicleCustomerResponse> vehicleCustomers;
        @Data @Builder
        public static class VehicleResponse {
            private Long id;
            private String clientId, plate, vehicleType, status;
            private SubscriptionResponse subscription;
        }
        @Data @Builder
        public static class CustomerResponse {
            private Long id, aiProfileId;
            private String clientId, name, phone, imageUrl, status, aiSyncState;
            /** Response compatibility only. Never accepted in a command. */
            @Deprecated private String profileId;
        }
        @Data @Builder
        public static class VehicleCustomerResponse {
            private Long id, vehicleId, customerId;
            private String relationType, status;
        }
    }
    @Data @Builder
    public static class ParkingCustomerResponse {
        private Long id, ownerId, aiProfileId;
        private String name, phone, imageUrl, status, aiSyncState;
        @Deprecated private String profileId;
        private OffsetDateTime createdAt, updatedAt;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class ParkingCustomerUpdateRequest extends OwnerProfileCommand {
        @Size(max = 255) private String name;
        @Size(max = 20) private String phone;
        @Pattern(regexp = "ACTIVE|INACTIVE") private String status;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SubscriptionRegistrantPageRequest {
        @Builder.Default private int page = 0;
        @Builder.Default private int size = 20;
        private String searchTerm, status, fromDate, toDate;
    }
    @Data @Builder
    public static class SubscriptionRegistrantPageResponse {
        private Long ownerId;
        private String fullName, customerCode, address, imageUrl, phone, zaloLink, note, status;
        private long registeredVehicleCount;
        private OffsetDateTime createdAt, updatedAt;
    }
    @Data @Builder
    public static class RegisteredVehicleResponse {
        private Long vehicleId;
        private String plate, vehicleType, vehicleStatus, userName, parkingLotName, buildingName;
        private String packageName, packageType, ticketType, subscriptionStatus;
        private OffsetDateTime validFrom, validTo;
        private boolean expired;
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class SubscriptionVehicleCreateRequest extends OwnerProfileCommand {
        @NotBlank @Size(max = 100) private String requestKey;
        @NotBlank @Size(max = 50) private String plate;
        @NotBlank @Size(max = 20) private String vehicleType;
        @Valid private ProfileSaveRequest.Subscription subscription;
        @Valid @Size(max = 100) private List<ProfileSaveRequest.Customer> customers;
    }
    @Data @Builder
    public static class SubscriptionVehicleCreateResponse {
        private Long vehicleId, ownerId;
        private String plate, vehicleType, status;
        private OffsetDateTime createdAt;
        private SubscriptionResponse subscription;
        private List<UserResponse> users;
        @Data @Builder
        public static class UserResponse {
            private Long customerId, aiProfileId;
            private String name, phone, imageUrl, aiSyncState;
            @Deprecated private String profileId;
            private String relationType, relationStatus;
        }
    }
    @Data @EqualsAndHashCode(callSuper = false)
    public static class VehicleUserSaveRequest extends OwnerProfileCommand {
        @NotBlank @Size(max = 100) private String requestKey;
        @Positive private Long customerId;
        private boolean ownerAsDriver;
        @Size(max = 255) private String name;
        @Size(max = 20) private String phone;
        @NotBlank @Pattern(regexp = "OWNER|AUTHORIZED_USER") private String relationType;
    }
}
