package vio.barie.core.dto.owneraccount;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vio.barie.core.dto.PaginationRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class OwnerAccountDtos {
    private OwnerAccountDtos() {
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerAccountPaginationRequest extends PaginationRequest {
        private String searchTerm;
        private String status;
        private LocalDate fromDate;
        private LocalDate toDate;
    }

    @Data
    public static class OwnerAccountRequest {
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

        @NotBlank(message = "status is required")
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
        public static class VehicleResponse {
            private Long id;
            private String clientId;
            private String plate;
            private String vehicleType;
            private String status;
        }

        @Data
        @Builder
        public static class CustomerResponse {
            private Long id;
            private String clientId;
            private String name;
            private String phone;
            private String imageUrl;
            private String profileId;
            private String status;
        }

        @Data
        @Builder
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
        private String profileId;
        private String status;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    public static class ParkingCustomerUpdateRequest {
        @Size(max = 255, message = "name must not exceed 255 characters")
        private String name;

        @Size(max = 30, message = "phone must not exceed 30 characters")
        private String phone;

        private String imageUrl;

        @Size(max = 255, message = "profileId must not exceed 255 characters")
        private String profileId;

        @Size(max = 20, message = "status must not exceed 20 characters")
        private String status;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VehicleTicketPageRequest extends PaginationRequest {
        private String searchTerm;
        private String status;
        private String vehicleType;
        private LocalDate fromDate;
        private LocalDate toDate;
    }

    @Data
    @Builder
    public static class VehicleTicketPageResponse {
        private Long vehicleId;
        private String plate;
        private String vehicleType;
        private String vehicleStatus;
        private Long ownerId;
        private String ownerName;
        private String ownerPhone;
        private String parkingAreaName;
        private String buildingName;
        private String ticketType;
        private String subscriptionCode;
        private OffsetDateTime expireAt;
        private String subscriptionStatus;
    }

    @Data
    @Builder
    public static class VehicleTicketDetailResponse {
        private Header header;
        private SubscriptionInfo subscriptionInfo;
        private List<UserInfo> users;
        private ParkingPermission parkingPermission;
        private List<RenewalHistory> renewalHistories;

        @Data
        @Builder
        public static class Header {
            private Long vehicleId;
            private String plate;
            private String vehicleType;
            private String imageUrl;
            private String subscriptionCode;
        }

        @Data
        @Builder
        public static class SubscriptionInfo {
            private Long ownerId;
            private String fullName;
            private String customerCode;
            private String address;
            private String phone;
        }

        @Data
        @Builder
        public static class UserInfo {
            private Long id;
            private String name;
            private String phone;
            private String imageUrl;
            private String profileId;
            private String relationType;
            private String status;
        }

        @Data
        @Builder
        public static class ParkingPermission {
            private String packageName;
            private String parkingAreaName;
            private String buildingName;
            private OffsetDateTime validFrom;
            private OffsetDateTime validTo;
            private BigDecimal subscriptionFee;
            private String currency;
        }

        @Data
        @Builder
        public static class RenewalHistory {
            private String orderCode;
            private OffsetDateTime startAt;
            private OffsetDateTime endAt;
            private BigDecimal amount;
            private String paymentStatus;
            private String transactionCode;
        }
    }

    @Data
    public static class SubscriptionRegistrantPageRequest {
        private String searchTerm;
        private String status;
        private Long buildingId;
        private Long parkingLotId;
        private LocalDate fromDate;
        private LocalDate toDate;
    }

    @Data
    @Builder
    public static class SubscriptionRegistrantPageResponse {
        private Long ownerId;
        private String fullName;
        private String customerCode;
        private String address;
        private String phone;
        private String zaloLink;
        private String status;
        private long registeredVehicleCount;
        private long carCount;
        private long motorbikeCount;
        private long expiredSubscriptionCount;
    }

    @Data
    @Builder
    public static class SubscriptionRegistrantSummaryResponse {
        private long totalSubscription;
        private long maxSubscription;
        private long activeSubscription;
        private long maxActiveSubscription;
        private long expiringSoonSubscription;
        private long expiredSubscription;
        private long blacklistCount;
    }

    @Data
    @Builder
    public static class SubscriptionRegistrantDetailResponse {
        private Long ownerId;
        private String fullName;
        private String customerCode;
        private String address;
        private String phone;
        private String imageUrl;
        private String zaloLink;
        private String status;
        private long registeredVehicleCount;
        private long activeSubscriptionCount;
        private long expiredSubscriptionCount;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    @Builder
    public static class SubscriptionRegisteredVehicleResponse {
        private Long vehicleId;
        private String plate;
        private String vehicleType;
        private String vehicleStatus;
        private String userName;
        private String parkingAreaName;
        private String buildingName;
        private String ticketType;
        private String subscriptionCode;
        private String subscriptionStatus;
        private OffsetDateTime validFrom;
        private OffsetDateTime validTo;
    }

    @Data
    @Builder
    public static class SubscriptionAccessHistoryResponse {
        private Long sessionId;
        private Long vehicleId;
        private String plate;
        private String vehicleType;
        private String parkingLotName;
        private String entryLaneName;
        private String exitLaneName;
        private String entryCustomerName;
        private String exitCustomerName;
        private OffsetDateTime entryTime;
        private OffsetDateTime exitTime;
        private String sessionType;
    }

    @Data
    @Builder
    public static class SubscriptionChangeHistoryResponse {
        private Long ticketId;
        private Long vehicleId;
        private String plate;
        private String ticketType;
        private String ticketStatus;
        private OffsetDateTime validFrom;
        private OffsetDateTime validTo;
        private OffsetDateTime changedAt;
        private String orderCode;
        private BigDecimal amount;
        private String paymentStatus;
        private String transactionCode;
    }

    @Data
    public static class SubscriptionVehicleCreateRequest {
        @NotBlank(message = "plate is required")
        @Size(max = 30, message = "plate must not exceed 30 characters")
        private String plate;

        @NotBlank(message = "vehicleType is required")
        @Size(max = 20, message = "vehicleType must not exceed 20 characters")
        private String vehicleType;

        @NotBlank(message = "vehicle status is required")
        @Size(max = 20, message = "vehicle status must not exceed 20 characters")
        private String status;

        @Valid
        private List<ExistingUser> users;

        @Valid
        private List<NewUser> newUsers;

        @Data
        public static class ExistingUser {
            @NotNull(message = "customerId is required")
            private Long customerId;

            @NotBlank(message = "relationType is required")
            @Size(max = 30, message = "relationType must not exceed 30 characters")
            private String relationType;

            @NotBlank(message = "user relation status is required")
            @Size(max = 20, message = "user relation status must not exceed 20 characters")
            private String status;
        }

        @Data
        public static class NewUser {
            @NotBlank(message = "name is required")
            @Size(max = 255, message = "name must not exceed 255 characters")
            private String name;

            @Size(max = 30, message = "phone must not exceed 30 characters")
            private String phone;

            private String imageUrl;

            @Size(max = 255, message = "profileId must not exceed 255 characters")
            private String profileId;

            @NotBlank(message = "relationType is required")
            @Size(max = 30, message = "relationType must not exceed 30 characters")
            private String relationType;

            @NotBlank(message = "user status is required")
            @Size(max = 20, message = "user status must not exceed 20 characters")
            private String status;
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

        @Data
        @Builder
        public static class UserResponse {
            private Long customerId;
            private String name;
            private String phone;
            private String imageUrl;
            private String profileId;
            private String relationType;
            private String relationStatus;
        }
    }

    @Data
    public static class VehicleUserSaveRequest {
        private Long customerId;

        @Size(max = 255, message = "name must not exceed 255 characters")
        private String name;

        @Size(max = 30, message = "phone must not exceed 30 characters")
        private String phone;

        private String imageUrl;

        @Size(max = 255, message = "profileId must not exceed 255 characters")
        private String profileId;

        @NotBlank(message = "relationType is required")
        @Size(max = 30, message = "relationType must not exceed 30 characters")
        private String relationType;

        @NotBlank(message = "status is required")
        @Size(max = 20, message = "status must not exceed 20 characters")
        private String status;
    }
}
