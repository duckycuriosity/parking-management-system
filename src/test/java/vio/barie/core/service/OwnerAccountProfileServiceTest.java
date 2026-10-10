package vio.barie.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.OwnerProfileRegistrationEntity;
import vio.barie.core.repository.*;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Registration command boundary and service behavior; concurrency lives in PostgreSQL tests. */
class OwnerAccountProfileServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final tools.jackson.databind.json.JsonMapper httpJson =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"aiProfileId", "profileId", "imageUrl", "status"})
    void ownerCannotSubmitBackendOwnedIdentityOrInternalStatus(String field) {
        String request = "{\"requestKey\":\"key\",\"owner\":{\"id\":1,\"" + field + "\":\"forged\"}}";

        assertRejectedByBothHttpAndInternalMappers(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"priceVnd", "endsAt", "paidAt", "paidReceiptId", "status", "registrationType"})
    void subscriptionCannotSubmitBackendOwnedPriceDatesOrPaymentState(String field) {
        String request = """
                {"requestKey":"key","owner":{"id":1},"vehicles":[
                  {"id":2,"subscription":{"%s":"forged"}}
                ]}
                """.formatted(field);

        assertRejectedByBothHttpAndInternalMappers(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"profileId", "aiProfileId", "aiIdentityId", "status", "relationStatus"})
    void customerCannotAttachForgedAiIdentityOrRelationState(String field) {
        String request = """
                {"requestKey":"key","owner":{"id":1},"vehicles":[
                  {"id":2,"customers":[{"id":3,"relationType":"AUTHORIZED_USER","%s":"forged"}]}
                ]}
                """.formatted(field);

        assertRejectedByBothHttpAndInternalMappers(request);
    }

    private void assertRejectedByBothHttpAndInternalMappers(String request) {
        assertThrows(JsonProcessingException.class, () -> json.readValue(request, ProfileSaveRequest.class));
        // Spring Boot 4's default HTTP converter uses Jackson 3. Both mapper generations
        // coexist in this repository and must honor the command's nested allowlist.
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> httpJson.readValue(request, ProfileSaveRequest.class));
    }

    @Test
    void existingSelectionsNeedOnlyIdsAndTheExplicitRelationChoice() {
        ProfileSaveRequest request = new ProfileSaveRequest();
        request.setRequestKey("select-existing");
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setId(1L);
        request.setOwner(owner);
        ProfileSaveRequest.Vehicle vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setId(2L);
        ProfileSaveRequest.Customer customer = new ProfileSaveRequest.Customer();
        customer.setId(3L);
        customer.setRelationType("AUTHORIZED_USER");
        vehicle.setCustomers(List.of(customer));
        request.setVehicles(List.of(vehicle));

        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertTrue(validation.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void completedRequestReplaysBeforeAnOwnerBecomesInactive() {
        Harness harness = new Harness();
        ProfileSaveRequest request = new ProfileSaveRequest();
        request.setRequestKey("saved-profile");
        var input = new ProfileSaveRequest.Owner();
        input.setFullName("Owner one");
        input.setPhone("0900000001");
        request.setOwner(input);
        var original = harness.service.createProfile(request);
        harness.owner.setStatus("INACTIVE");

        var replay = harness.service.createProfile(request);

        assertEquals(original.getId(), replay.getId());
        assertEquals("INACTIVE", replay.getStatus());
        verify(harness.owners, times(1)).saveAndFlush(any());
        verify(harness.owners, never()).findForUpdate(any());
        verify(harness.subscriptions, never()).register(any());
        verifyNoInteractions(harness.identities);
    }

    @Test
    void selectingOwnerAndSendingCreationFieldsIsRejectedWithoutChangingItsData() {
        Harness harness = new Harness();
        ProfileSaveRequest request = new ProfileSaveRequest();
        request.setRequestKey("ambiguous-owner");
        var owner = new ProfileSaveRequest.Owner();
        owner.setId(1L);
        owner.setFullName("Attempted overwrite");
        request.setOwner(owner);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> harness.service.createProfile(request));

        assertEquals(400, failure.getHttpStatus());
        verify(harness.owners, never()).saveAndFlush(any());
        verify(harness.owners, never()).findForUpdate(any());
        verifyNoInteractions(harness.vehicles, harness.customers, harness.relations,
                harness.subscriptions, harness.identities);
    }

    @Test
    void ownerScopeIsCheckedBeforeExistingVehicleCanReceiveDriverOrSubscription() {
        Harness harness = new Harness();
        when(harness.owners.findForUpdate(1L)).thenReturn(Optional.of(harness.owner));
        when(harness.vehicles.findOwnedForUpdate(99L, 1L)).thenReturn(Optional.empty());
        ProfileSaveRequest request = new ProfileSaveRequest();
        request.setRequestKey("foreign-vehicle");
        var owner = new ProfileSaveRequest.Owner();
        owner.setId(1L);
        request.setOwner(owner);
        var vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setId(99L);
        request.setVehicles(List.of(vehicle));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> harness.service.createProfile(request));

        assertEquals(404, failure.getHttpStatus());
        verifyNoInteractions(harness.customers, harness.relations, harness.subscriptions, harness.identities);
        verify(harness.vehicles, never()).saveAndFlush(any());
    }

    @Test
    void integrityTranslationPreservesUnexpectedConstraintFailures() {
        var unknownUnique = constraintFailure("new_unrecognized_unique_constraint", "23505");
        var knownNameWrongState = constraintFailure("uk_owner_account_phone", "23514");
        var unexpectedDeleteUnique = constraintFailure("uk_owner_account_phone", "23505");

        assertSame(unknownUnique, OwnerProfileIntegrity.uniqueConflict(unknownUnique));
        assertSame(knownNameWrongState, OwnerProfileIntegrity.uniqueConflict(knownNameWrongState));
        assertSame(unexpectedDeleteUnique, OwnerProfileIntegrity.ownerDeleteConflict(unexpectedDeleteUnique));
    }

    private DataIntegrityViolationException constraintFailure(String name, String sqlState) {
        var provider = new ConstraintViolationException("Database rejected write",
                new SQLException("Provider constraint failure", sqlState), name);
        return new DataIntegrityViolationException("Could not flush", new IllegalStateException("Provider wrapper", provider));
    }

    private static final class Harness {
        final OwnerAccountRepository owners = mock(OwnerAccountRepository.class);
        final VehicleRepository vehicles = mock(VehicleRepository.class);
        final ParkingCustomerRepository customers = mock(ParkingCustomerRepository.class);
        final VehicleCustomerRepository relations = mock(VehicleCustomerRepository.class);
        final OwnerProfileRegistrationRepository registrations = mock(OwnerProfileRegistrationRepository.class);
        final VehicleSubscriptionService subscriptions = mock(VehicleSubscriptionService.class);
        final OwnerAccountAiIdentityService identities = mock(OwnerAccountAiIdentityService.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final OwnerAccountEntity owner = new OwnerAccountEntity();
        final Map<String, OwnerProfileRegistrationEntity> committed = new HashMap<>();
        final OwnerAccountProfileService service = new OwnerAccountProfileService(owners, vehicles, customers,
                relations, registrations, subscriptions, identities, entityManager);

        Harness() {
            owner.setId(1L);
            owner.setStatus("ACTIVE");
            when(registrations.findById(anyString())).thenAnswer(call -> Optional.ofNullable(committed.get(call.getArgument(0))));
            when(registrations.saveAndFlush(any())).thenAnswer(call -> {
                OwnerProfileRegistrationEntity value = call.getArgument(0);
                committed.put(value.getRequestKey(), value);
                return value;
            });
            when(owners.saveAndFlush(any())).thenAnswer(call -> {
                OwnerAccountEntity value = call.getArgument(0);
                value.setId(1L);
                owner.setFullName(value.getFullName());
                owner.setPhone(value.getPhone());
                owner.setStatus(value.getStatus());
                return owner;
            });
            when(owners.findById(1L)).thenReturn(Optional.of(owner));
        }
    }
}
