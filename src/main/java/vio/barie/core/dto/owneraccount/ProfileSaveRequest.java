package vio.barie.core.dto.owneraccount;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** A confirmation command: existing objects use only their IDs; new objects use their input fields. */
@Data
public class ProfileSaveRequest extends OwnerAccountDtos.Input {
    @Valid @NotNull
    private Owner owner;
    @Valid @NotEmpty @Size(max = 50)
    private List<Vehicle> vehicles;

    @Data
    public static class Owner extends OwnerAccountDtos.Input {
        @Positive private Long id;
        @Size(max = 255) private String fullName;
        @Size(max = 30) private String phone;
        @Size(max = 30) private String identityNumber;
        private String address;
        private String zaloLink;
        private String note;
        @Size(max = 5) private List<String> images;
    }

    @Data
    public static class Vehicle extends OwnerAccountDtos.Input {
        @Positive private Long id;
        @Size(max = 30) private String plate;
        @Size(max = 20) private String vehicleType;
        @Valid private Subscription subscription;
        @Valid @Size(max = 50) private List<Customer> customers;
    }

    @Data
    public static class Subscription extends OwnerAccountDtos.Input {
        @NotNull @Positive private Long parkingLotId;
        @NotNull @Positive private Long packageId;
        @NotNull private OffsetDateTime startsAt;
        @NotNull private OffsetDateTime endsAt;
        @NotNull private OffsetDateTime paymentDeadlineAt;
        @Positive private Long previousSubscriptionId;
    }

    @Data
    public static class Customer extends OwnerAccountDtos.Input {
        @Positive private Long id;
        /** An explicit reference shared by new-person entries in this request; never stored in DB. */
        @Size(max = 100) private String clientId;
        @Size(max = 255) private String name;
        @Size(max = 20) private String phone;
        @JsonAlias("indexs") private List<Integer> imageIndexes;
        @Size(max = 5) private List<String> images;
        /** OWNER creates the owner-person only with a new owner; every new person requires an explicit role. */
        @Size(max = 30) private String relationType;
    }
}
