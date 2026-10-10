package vio.barie.core.dto.owneraccount;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.OffsetDateTime;
import java.util.List;

/** Select by ID or supply creation data; never replace the persisted entity graph. */
@Data
@EqualsAndHashCode(callSuper = false)
public class ProfileSaveRequest extends OwnerProfileCommand {
    @NotBlank @Size(max = 100) private String requestKey;
    @Valid @NotNull private Owner owner;
    @Valid @Size(max = 100) private List<Vehicle> vehicles;

    @Data @EqualsAndHashCode(callSuper = false)
    public static class Owner extends OwnerProfileCommand {
        @Positive private Long id;
        @Size(max = 255) private String fullName;
        @Size(max = 30) private String phone;
        @Size(max = 30) private String identityNumber;
        private String address;
        private String zaloLink;
        private String note;
    }

    @Data @EqualsAndHashCode(callSuper = false)
    public static class Vehicle extends OwnerProfileCommand {
        @Positive private Long id;
        @Size(max = 100) private String clientId;
        @Size(max = 50) private String plate;
        @Size(max = 20) private String vehicleType;
        @Valid private Subscription subscription;
        @Valid @Size(max = 100) private List<Customer> customers;
    }

    @Data @EqualsAndHashCode(callSuper = false)
    public static class Subscription extends OwnerProfileCommand {
        @NotNull @Positive private Long parkingLotId;
        @NotNull @Positive private Long packageId;
        @NotNull private OffsetDateTime startsAt;
        @NotNull private OffsetDateTime paymentDeadlineAt;
    }

    @Data @EqualsAndHashCode(callSuper = false)
    public static class Customer extends OwnerProfileCommand {
        @Positive private Long id;
        /** Reuse this draft key for one new person across different vehicles. */
        @Size(max = 100) private String clientId;
        private boolean ownerAsDriver;
        @Size(max = 255) private String name;
        @Size(max = 20) private String phone;
        @NotBlank @Size(max = 30) private String relationType;
    }
}
