package vio.barie.core.dto.owneraccount;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProfileSaveRequestTest {
    private final ObjectMapper json = new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void existingObjectsRequireOnlyIdsAndRegistrationKey() {
        ProfileSaveRequest request = new ProfileSaveRequest();
        request.setRequestKey("confirm-1");
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setId(1L);
        request.setOwner(owner);
        ProfileSaveRequest.Vehicle vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setId(2L);
        ProfileSaveRequest.Customer driver = new ProfileSaveRequest.Customer();
        driver.setId(3L);
        vehicle.setCustomers(List.of(driver));
        request.setVehicles(List.of(vehicle));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void optionalIdentityNumberDoesNotBlockNewOwner() {
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setFullName("Owner");
        owner.setPhone("0900000000");

        assertThat(validator.validate(owner)).isEmpty();
    }

    @Test
    void clientCannotDeserializePriceOrPaymentStateIntoSubscriptionCommand() {
        for (String field : List.of("priceVnd", "endsAt", "status", "paidAt", "paymentSource", "paymentReference", "paidReceiptId")) {
            assertThatThrownBy(() -> json.readValue("{\"" + field + "\":1}", ProfileSaveRequest.Subscription.class))
                    .isInstanceOf(JsonMappingException.class);
        }
    }

    @Test
    void clientCannotAssignAiProfileIdToPerson() {
        for (String field : List.of("profileId", "aiProfileId", "aiIdentityId", "status")) {
            assertThatThrownBy(() -> json.readValue("{\"" + field + "\":1}", ProfileSaveRequest.Customer.class))
                    .isInstanceOf(JsonMappingException.class);
        }
    }

    @Test
    void legacyImageIndexSpellingStillAccepted() throws Exception {
        ProfileSaveRequest.Customer driver = json.readValue("{\"name\":\"Driver\",\"indexs\":[0,2]}", ProfileSaveRequest.Customer.class);
        assertThat(driver.getImageIndexes()).containsExactly(0, 2);
    }

    @Test
    void subscriptionRequiresExplicitPaymentDeadlineRatherThanInventedTtl() {
        ProfileSaveRequest.Subscription subscription = new ProfileSaveRequest.Subscription();
        subscription.setParkingLotId(1L);
        subscription.setPackageId(2L);
        subscription.setStartsAt(java.time.OffsetDateTime.now());

        assertThat(validator.validate(subscription)).singleElement().satisfies(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("paymentDeadlineAt"));
    }
}
