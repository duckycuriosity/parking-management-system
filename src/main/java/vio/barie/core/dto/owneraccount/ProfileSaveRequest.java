package vio.barie.core.dto.owneraccount;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class ProfileSaveRequest {
    @Valid
    @NotNull(message = "owner is required")
    private Owner owner;

    @Valid
    @NotEmpty(message = "vehicles is required")
    private List<Vehicle> vehicles;

    @Data
    public static class Owner {
        private Long id;

        @NotBlank(message = "fullName is required")
        @Size(max = 255, message = "fullName must not exceed 255 characters")
        private String fullName;

        @NotBlank(message = "phone is required")
        @Size(max = 30, message = "phone must not exceed 30 characters")
        private String phone;

        @NotBlank(message = "identityNumber is required")
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
    public static class Vehicle {
        private Long id;

        @NotBlank(message = "plate is required")
        @Size(max = 30, message = "plate must not exceed 30 characters")
        private String plate;

        @NotBlank(message = "vehicleType is required")
//        @Size(max = 20, message = "vehicleType must not exceed 20 characters")
        private String vehicleType;

        @NotBlank(message = "vehicle status is required")
//        @Size(max = 20, message = "vehicle status must not exceed 20 characters")
        private String status;

        @Valid
        private Subscription subscription;

        @Valid
        @NotEmpty(message = "customers is required")
        private List<Customer> customers;
    }

    @Data
    public static class Subscription {
        private Long id;

        @NotNull(message = "parkingLotId is required")
        private Long parkingLotId;

        @NotNull(message = "packageId is required")
        private Long packageId;

//        @NotBlank(message = "registrationType is required")
//        @Size(max = 20, message = "registrationType must not exceed 20 characters")
//        private String registrationType;

//        private Long previousSubscriptionId;

//        @NotNull(message = "priceVnd is required")
//        private Long priceVnd;

        @NotNull(message = "startsAt is required")
        private OffsetDateTime startsAt;

        @NotNull(message = "endsAt is required")
        private OffsetDateTime endsAt;

//        private OffsetDateTime paymentDeadlineAt;

//        @NotBlank(message = "subscription status is required")
//        @Size(max = 30, message = "subscription status must not exceed 30 characters")
//        private String status;

//        private OffsetDateTime paidAt;

//        @Size(max = 50, message = "paymentSource must not exceed 50 characters")
//        private String paymentSource;

//        @Size(max = 255, message = "paymentReference must not exceed 255 characters")
//        private String paymentReference;

//        private String paymentReceipt;
    }

    @Data
    public static class Customer {
        private Long id;
//        private Long relationId;
//        @JsonAlias({"imageIndexes", "indexes"})
//        private List<Integer> indexs;
//        private Boolean isUsed;

        @NotBlank(message = "customer name is required")
        @Size(max = 255, message = "customer name must not exceed 255 characters")
        private String name;

//        @Size(max = 30, message = "customer phone must not exceed 30 characters")
        private String phone;

//        private String imageUrl;
//        private String aiIdentityId;

//        @Size(max = 255, message = "profileId must not exceed 255 characters")
//        private String profileId;

//        @NotBlank(message = "customer status is required")
//        @Size(max = 20, message = "customer status must not exceed 20 characters")
//        private String status;

        @NotBlank(message = "relationType is required")
        @Size(max = 30, message = "relationType must not exceed 30 characters")
        private String relationType;

//        @NotBlank(message = "relation status is required")
//        @Size(max = 20, message = "relation status must not exceed 20 characters")
//        private String relationStatus;
    }
}
