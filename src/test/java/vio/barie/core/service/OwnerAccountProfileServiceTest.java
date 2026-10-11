package vio.barie.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerAccountProfileServiceTest {
    @Mock AiFaceApiClient ai;
    @Mock OwnerAccountRepository owners;
    @Mock VehicleRepository vehicles;
    @Mock ParkingCustomerRepository customers;
    @Mock VehicleCustomerRepository relations;
    @Mock VehicleSubscriptionService subscriptions;
    @Mock PlatformTransactionManager transactions;
    @Mock ProfileRegistrationGuard guard;
    @InjectMocks OwnerAccountProfileService service;

    private final MockMultipartFile image = new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[]{1, 2});

    @BeforeEach
    void passthroughGuard() {
        lenient().when(guard.execute(anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    ((Runnable) call.getArgument(5)).run();
                    return ((Supplier<?>) call.getArgument(6)).get();
                });
    }

    @Test
    void newPersonUsesBackendAiIdAndRegistersOrderAfterDriverAndRelationSave() {
        ProfileSaveRequest request = newRegistration();
        stubNewGraph();
        VehicleSubscriptionEntity order = new VehicleSubscriptionEntity();
        order.setId(77L);
        order.setPriceVnd(100_000L);
        order.setStatus("PENDING_PAYMENT");
        when(subscriptions.register(anyLong(), any())).thenReturn(order);

        var result = service.createProfile(request, List.of(image), null);

        assertThat(result.getCustomers()).singleElement().extracting("aiProfileId").isEqualTo(123);
        assertThat(result.getVehicles()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.getSubscription().getId()).isEqualTo(77L);
            assertThat(vehicle.getSubscription().getPriceVnd()).isEqualTo(100_000L);
            assertThat(vehicle.getSubscription().getStatus()).isEqualTo("PENDING_PAYMENT");
        });
        InOrder sequence = inOrder(ai, transactions, owners, customers, relations, subscriptions);
        sequence.verify(ai).createProfile(eq("Driver"), anyList());
        sequence.verify(transactions).getTransaction(any());
        sequence.verify(owners).save(any());
        sequence.verify(customers).save(argThat(person -> person.getAiProfileId().equals(123) && person.getAiIdentityId().equals("uuid-123")));
        sequence.verify(relations).save(any());
        sequence.verify(subscriptions).register(anyLong(), same(request.getVehicles().getFirst().getSubscription()));
        sequence.verify(transactions).commit(any());
    }

    @Test
    void ownerAsDriverSharedAcrossTwoVehiclesCreatesOneAiProfileAndOnePerson() {
        ProfileSaveRequest request = newRegistration();
        ProfileSaveRequest.Customer driver = request.getVehicles().getFirst().getCustomers().getFirst();
        driver.setName(null);
        driver.setPhone(null);
        driver.setImageIndexes(null);
        driver.setOwnerDriver(true);
        ProfileSaveRequest.Vehicle second = new ProfileSaveRequest.Vehicle();
        second.setPlate("99A54321");
        second.setVehicleType("CAR");
        ProfileSaveRequest.Customer sameOwner = new ProfileSaveRequest.Customer();
        sameOwner.setOwnerDriver(true);
        second.setCustomers(List.of(sameOwner));
        request.setVehicles(List.of(request.getVehicles().getFirst(), second));
        stubNewGraph();

        var result = service.createProfile(request, null, List.of(image));

        verify(ai).createProfile(eq("Owner"), anyList());
        verify(customers).save(any());
        verify(relations, times(2)).save(any());
        assertThat(result.getCustomers()).hasSize(1);
        assertThat(result.getVehicleCustomers()).extracting("customerId").containsOnly(20L);
    }

    @Test
    void explicitExistingSelectionsDoNotUpdateObjectsCallAiOrRevokeOmittedRelations() {
        ProfileSaveRequest request = newRegistration();
        ProfileSaveRequest.Owner selected = new ProfileSaveRequest.Owner();
        selected.setId(1L);
        request.setOwner(selected);
        ProfileSaveRequest.Vehicle vehicle = request.getVehicles().getFirst();
        vehicle.setId(10L);
        vehicle.setPlate(null);
        vehicle.setVehicleType(null);
        ProfileSaveRequest.Customer driver = new ProfileSaveRequest.Customer();
        driver.setId(20L);
        vehicle.setCustomers(List.of(driver));
        OwnerAccountEntity owner = new OwnerAccountEntity();
        owner.setId(1L);
        VehicleEntity savedVehicle = new VehicleEntity();
        savedVehicle.setId(10L);
        savedVehicle.setOwnerId(1L);
        savedVehicle.setVehicleType("CAR");
        ParkingCustomerEntity savedDriver = new ParkingCustomerEntity();
        savedDriver.setId(20L);
        savedDriver.setOwnerId(1L);
        when(owners.findById(1L)).thenReturn(Optional.of(owner));
        when(vehicles.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(savedVehicle));
        when(customers.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(savedDriver));
        stubTransactionAndRelations();

        service.createProfile(request);

        verifyNoInteractions(ai);
        verify(owners, never()).save(any());
        verify(vehicles, never()).save(any());
        verify(customers, never()).save(any());
        verify(customers, never()).findAll();
        verify(relations, never()).findByOwnerId(anyLong());
        verify(relations, never()).saveAll(any());
    }

    @Test
    void existingVehicleCanRegisterPackageWithoutResendingItsDrivers() {
        ProfileSaveRequest request = newRegistration();
        ProfileSaveRequest.Owner selectedOwner = new ProfileSaveRequest.Owner();
        selectedOwner.setId(1L);
        request.setOwner(selectedOwner);
        ProfileSaveRequest.Vehicle selectedVehicle = request.getVehicles().getFirst();
        selectedVehicle.setId(10L);
        selectedVehicle.setPlate(null);
        selectedVehicle.setVehicleType(null);
        selectedVehicle.setCustomers(null);
        OwnerAccountEntity owner = new OwnerAccountEntity();
        owner.setId(1L);
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setId(10L);
        vehicle.setOwnerId(1L);
        vehicle.setVehicleType("CAR");
        when(owners.findById(1L)).thenReturn(Optional.of(owner));
        when(vehicles.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(vehicle));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        VehicleSubscriptionEntity order = new VehicleSubscriptionEntity();
        order.setId(77L);
        when(subscriptions.register(eq(10L), any())).thenReturn(order);

        var result = service.createProfile(request);

        assertThat(result.getVehicles()).singleElement().satisfies(saved ->
                assertThat(saved.getSubscription().getId()).isEqualTo(77L));
        assertThat(result.getCustomers()).isEmpty();
        assertThat(result.getVehicleCustomers()).isEmpty();
        verifyNoInteractions(ai, customers, relations);
        verify(subscriptions).register(10L, selectedVehicle.getSubscription());
        verify(vehicles, never()).save(any());
    }

    @Test
    void foreignDriverRejectedBeforeAiOrWrites() {
        ProfileSaveRequest request = newRegistration();
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setId(1L);
        request.setOwner(owner);
        OwnerAccountEntity existing = new OwnerAccountEntity();
        existing.setId(1L);
        when(owners.findById(1L)).thenReturn(Optional.of(existing));
        ProfileSaveRequest.Customer foreign = new ProfileSaveRequest.Customer();
        foreign.setId(999L);
        request.getVehicles().getFirst().setCustomers(List.of(foreign));

        assertThatThrownBy(() -> service.createProfile(request)).isInstanceOf(ServiceException.class).hasMessageContaining("does not belong");
        verifyNoInteractions(ai, transactions, relations);
        verify(customers, never()).save(any());
    }

    @Test
    void invalidImageIndexRejectedBeforeAiOrTransaction() {
        ProfileSaveRequest request = newRegistration();
        request.getVehicles().getFirst().getCustomers().getFirst().setImageIndexes(List.of(9));

        assertThatThrownBy(() -> service.createProfile(request, List.of(image), null)).isInstanceOf(ServiceException.class).hasMessageContaining("image index");
        verifyNoInteractions(ai, transactions);
    }

    @Test
    void packageFailureRejectedBeforeAi() {
        ProfileSaveRequest request = newRegistration();
        doThrow(new ServiceException("Wrong parking lot", 400)).when(subscriptions).validateRegistration(any(), anyString());

        assertThatThrownBy(() -> service.createProfile(request, List.of(image), null)).hasMessage("Wrong parking lot");
        verifyNoInteractions(ai, transactions);
    }

    @Test
    void malformedAiResponseDoesNotSaveLocalGraph() {
        AiProfileResponse invalid = new AiProfileResponse();
        invalid.setId(123);
        when(ai.createProfile(anyString(), anyList())).thenReturn(invalid);

        assertThatThrownBy(() -> service.createProfile(newRegistration(), List.of(image), null)).isInstanceOf(ServiceException.class).hasMessageContaining("invalid profile");
        verifyNoInteractions(transactions);
        verify(owners, never()).save(any());
        verify(customers, never()).save(any());
    }

    @Test
    void failedOrderRollsBackLocalTransactionAndDoesNotCommit() {
        stubNewGraph();
        doThrow(new ServiceException("Order failed", 400)).when(subscriptions).register(anyLong(), any());

        assertThatThrownBy(() -> service.createProfile(newRegistration(), List.of(image), null)).hasMessage("Order failed");
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void customerPatchChecksOwnerAndDoesNotTouchAiIdentityOrUnspecifiedFields() {
        ParkingCustomerEntity customer = new ParkingCustomerEntity();
        customer.setId(20L);
        customer.setOwnerId(1L);
        customer.setUsername("Before");
        customer.setPhoneNumber("0900000000");
        customer.setAiProfileId(123);
        customer.setAiIdentityId("uuid-123");
        customer.setStatus("INACTIVE");
        when(customers.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(customer));
        when(customers.save(any())).thenAnswer(call -> call.getArgument(0));
        OwnerAccountDtos.ParkingCustomerUpdateRequest patch = new OwnerAccountDtos.ParkingCustomerUpdateRequest();
        patch.setName("After");

        var result = service.updateCustomer(1L, 20L, patch);

        assertThat(result.getName()).isEqualTo("After");
        assertThat(result.getPhone()).isEqualTo("0900000000");
        assertThat(result.getAiProfileId()).isEqualTo(123);
        assertThat(result.getStatus()).isEqualTo("INACTIVE");
        verifyNoInteractions(ai, relations);
    }

    private void stubNewGraph() {
        AiProfileResponse face = new AiProfileResponse();
        face.setId(123);
        face.setUuid("uuid-123");
        when(ai.createProfile(anyString(), anyList())).thenReturn(face);
        when(owners.save(any())).thenAnswer(call -> {
            OwnerAccountEntity entity = call.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        AtomicLong vehicleId = new AtomicLong(10);
        when(vehicles.save(any())).thenAnswer(call -> {
            VehicleEntity entity = call.getArgument(0);
            entity.setId(vehicleId.getAndIncrement());
            return entity;
        });
        when(customers.save(any())).thenAnswer(call -> {
            ParkingCustomerEntity entity = call.getArgument(0);
            entity.setId(20L);
            return entity;
        });
        stubTransactionAndRelations();
    }

    private void stubTransactionAndRelations() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(relations.save(any())).thenAnswer(call -> {
            VehicleCustomerEntity entity = call.getArgument(0);
            entity.setId(30L);
            return entity;
        });
    }

    private ProfileSaveRequest newRegistration() {
        ProfileSaveRequest request = new ProfileSaveRequest();
        request.setRequestKey("registration-1");
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setFullName("Owner");
        owner.setPhone("0900000001");
        request.setOwner(owner);
        ProfileSaveRequest.Vehicle vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setPlate("99A12345");
        vehicle.setVehicleType("CAR");
        ProfileSaveRequest.Subscription subscription = new ProfileSaveRequest.Subscription();
        subscription.setParkingLotId(1L);
        subscription.setPackageId(2L);
        vehicle.setSubscription(subscription);
        ProfileSaveRequest.Customer customer = new ProfileSaveRequest.Customer();
        customer.setName("Driver");
        customer.setPhone("0900000002");
        customer.setImageIndexes(List.of(0));
        vehicle.setCustomers(List.of(customer));
        request.setVehicles(List.of(vehicle));
        return request;
    }
}
