package vio.barie.core.dto.owneraccount;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

public final class OwnerAccountDtos {
    private OwnerAccountDtos() {
    }

    public abstract static class Input {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unsupported request field: " + field);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerAccountPaginationRequest {
        @Builder.Default
        private int page = 0;

        @Builder.Default
        private int size = 20;

        private String searchTerm;
        private String status;
        private String fromDate;
        private String toDate;
    }

    @Data
    public static class OwnerAccountRequest extends Input {
        @NotBlank(message = "fullName is required")
        @Size(max = 255, message = "fullName must not exceed 255 characters")
        private String fullName;

        @NotBlank(message = "phone is required")
        @Size(max = 30, message = "phone must not exceed 30 characters")
        private String phone;

        @Size(max = 30, message = "identityNumber must not exceed 30 characters")
        private String identityNumber;

        private String address;
        private String imageUrl;
        private String zaloLink;
        private String note;

        @Size(max = 20, message = "status must not exceed 20 characters")
        private String status;
    }

    @Data
    @Builder
    public static class OwnerAccountResponse {
        private Long id;
        private String fullName;
        private String phone;
        private String identityNumber;
        private String address;
        private String imageUrl;
        private String zaloLink;
        private String note;
        private String status;
        private Long vehicleCount;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerAccountVehicleParkingCustomerResponse {
        private Long id;
        private String fullName;
        private String phone;
        private String identityNumber;
        private String address;
        private String imageUrl;
        private String zaloLink;
        private String note;
        private String status;
        private Long vehicleCount;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private List<VehicleResponse> vehicles;
        private List<CustomerResponse> customers;
        private List<VehicleCustomerResponse> vehicleCustomers;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class VehicleResponse {
            private Long id;
            private String clientId;
            private String plate;
            private String vehicleType;
            private String status;
            private OffsetDateTime createdAt;
            private SubscriptionResponse subscription;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class CustomerResponse {
            private Long id;
            private String clientId;
            private String name;
            private String phone;
            private String imageUrl;
            private Integer aiProfileId;
            private String status;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class VehicleCustomerResponse {
            private Long id;
            private Long vehicleId;
            private Long customerId;
            private String relationType;
            private String status;
        }
    }

    @Data
    @Builder
    public static class ParkingCustomerResponse {
        private Long id;
        private Long ownerId;
        private String name;
        private String phone;
        private String imageUrl;
        private Integer aiProfileId;
        private String status;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    public static class ParkingCustomerUpdateRequest extends Input {
        @Size(max = 255) private String name;
        @Size(max = 20) private String phone;
        @Size(max = 20) private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionRegistrantPageRequest {
        @Builder.Default
        private int page = 0;

        @Builder.Default
        private int size = 20;

        private String searchTerm;
        private String status;
        private String fromDate;
        private String toDate;
    }

    @Data
    @Builder
    public static class SubscriptionRegistrantPageResponse {
        private Long ownerId;
        private String fullName;
        private String customerCode;
        private String address;
        private String imageUrl;
        private String phone;
        private String zaloLink;
        private String note;
        private String status;
        private long registeredVehicleCount;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    @Builder
    public static class RegisteredVehicleResponse {
        private Long vehicleId;
        private String plate;
        private String vehicleType;
        private String vehicleStatus;
        private String userName;
        private String parkingLotName;
        private String buildingName;
        private String packageName;
        private String packageType;
        private String ticketType;
        private String subscriptionStatus;
        private OffsetDateTime validFrom;
        private OffsetDateTime validTo;
        private boolean expired;
    }

    @Data
    public static class SubscriptionVehicleCreateRequest extends Input {
        @NotBlank @Size(max = 30) private String plate;
        @NotBlank @Size(max = 20) private String vehicleType;
        @Valid private ProfileSaveRequest.Subscription subscription;
        @Valid private List<ExistingUser> users;
        @Valid private List<NewUser> newUsers;

        @Data
        public static class ExistingUser extends Input {
            @NotNull private Long customerId;
            @Size(max = 30) private String relationType;
        }

        @Data
        public static class NewUser extends Input {
            @NotBlank @Size(max = 255) private String name;
            @Size(max = 20) private String phone;
            @com.fasterxml.jackson.annotation.JsonAlias("indexs")
            private List<Integer> imageIndexes;
            @Size(max = 30) private String relationType;
        }
    }

    @Data
    @Builder
    public static class SubscriptionVehicleCreateResponse {
        private Long vehicleId;
        private Long ownerId;
        private String plate;
        private String vehicleType;
        private String status;
        private OffsetDateTime createdAt;
        private List<UserResponse> users;
        private SubscriptionResponse subscription;

        @Data
        @Builder
        public static class UserResponse {
            private Long customerId;
            private String name;
            private String phone;
            private String imageUrl;
            private Integer aiProfileId;
            private String relationType;
            private String relationStatus;
        }
    }

    @Data
    public static class VehicleUserSaveRequest extends Input {
        @NotNull private Long customerId;
        @Size(max = 30) private String relationType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionResponse {
        private Long id;
        private Long packageId;
        private Long priceVnd;
        private String registrationType;
        private String status;
        private OffsetDateTime startsAt;
        private OffsetDateTime endsAt;
        private OffsetDateTime paymentDeadlineAt;
    }
}
