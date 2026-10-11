package vio.barie.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.repository.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerAccountServiceTest {
    @Mock OwnerAccountRepository owners;
    @Mock VehicleRepository vehicles;
    @Mock ParkingCustomerRepository customers;
    @Mock VehicleSubscriptionRepository subscriptions;
    @Mock VehicleCustomerRepository relations;
    @InjectMocks OwnerAccountService service;

    @Test
    void paginatedListCountsVehiclesInOneBatch() {
        OwnerAccountEntity first = owner(1L);
        OwnerAccountEntity second = owner(2L);
        when(owners.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second)));
        VehicleRepository.OwnerVehicleCount count = mock(VehicleRepository.OwnerVehicleCount.class);
        when(count.getOwnerId()).thenReturn(1L);
        when(count.getVehicleCount()).thenReturn(3L);
        when(vehicles.countByOwnerIds(List.of(1L, 2L))).thenReturn(List.of(count));

        var result = service.list(OwnerAccountDtos.OwnerAccountPaginationRequest.builder().build());

        assertThat(result.getContent()).extracting("vehicleCount").containsExactly(3L, 0L);
        verify(vehicles).countByOwnerIds(List.of(1L, 2L));
        verify(vehicles, never()).countByOwnerId(anyLong());
    }

    @Test
    void emptyListDoesNotQueryVehicleCounts() {
        when(owners.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(OwnerAccountDtos.OwnerAccountPaginationRequest.builder().build())).isEmpty();

        verifyNoInteractions(vehicles);
    }

    @Test
    void ownerWithVehiclesCannotBeDeletedLeavingOrphans() {
        when(owners.findById(1L)).thenReturn(Optional.of(owner(1L)));
        when(vehicles.existsByOwnerId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ServiceException.class).hasMessageContaining("owned vehicles and drivers");

        verify(owners, never()).delete(any(OwnerAccountEntity.class));
    }

    @Test
    void ownerWithDriversCannotBeDeletedLeavingOrphans() {
        when(owners.findById(1L)).thenReturn(Optional.of(owner(1L)));
        when(customers.existsByOwnerId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ServiceException.class);

        verify(owners, never()).delete(any(OwnerAccountEntity.class));
    }

    @Test
    void ownerUpdatePreservesStatusWhenNotSubmitted() {
        OwnerAccountEntity owner = owner(1L);
        owner.setStatus("INACTIVE");
        when(owners.findById(1L)).thenReturn(Optional.of(owner));
        when(owners.save(any())).thenAnswer(call -> call.getArgument(0));
        OwnerAccountDtos.OwnerAccountRequest request = new OwnerAccountDtos.OwnerAccountRequest();
        request.setFullName("After");
        request.setPhone("0900000000");

        assertThat(service.update(1L, request).getStatus()).isEqualTo("INACTIVE");
    }

    private OwnerAccountEntity owner(Long id) {
        OwnerAccountEntity owner = new OwnerAccountEntity();
        owner.setId(id);
        owner.setFullName("Owner " + id);
        owner.setPhone("090000000" + id);
        owner.setStatus("ACTIVE");
        return owner;
    }
}
