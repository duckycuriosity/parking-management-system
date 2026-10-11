package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.entity.*;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VehicleSubscriptionServiceTest {
    private final EntityManager em = mock(EntityManager.class);
    private final VehicleSubscriptionRepository repository = mock(VehicleSubscriptionRepository.class);
    private final VehicleSubscriptionService service = new VehicleSubscriptionService(repository, em);
    private SubscriptionPackageEntity pack;
    private ProfileSaveRequest.Subscription request;
    private VehicleEntity vehicle;

    @BeforeEach void setup() {
        pack = new SubscriptionPackageEntity();
        pack.setId(2L); pack.setParkingLotId(3L); pack.setVehicleType("CAR");
        pack.setStatus("PUBLISHED"); pack.setSaleEnabled(true);
        pack.setPriceVnd(150_000L); pack.setDurationDays(30);
        pack.setEffectiveFrom(OffsetDateTime.now().minusDays(2));
        request = new ProfileSaveRequest.Subscription();
        request.setPackageId(2L); request.setParkingLotId(3L);
        request.setStartsAt(OffsetDateTime.now(ZoneOffset.ofHours(7)).withNano(0).plusDays(1));
        request.setPaymentDeadlineAt(OffsetDateTime.now().withNano(0).plusHours(2));
        vehicle = new VehicleEntity();
        vehicle.setId(1L); vehicle.setOwnerId(4L); vehicle.setVehicleType("CAR"); vehicle.setStatus("ACTIVE");
        when(em.find(SubscriptionPackageEntity.class, 2L)).thenReturn(pack);
        when(em.find(SubscriptionPackageEntity.class, 2L, LockModeType.PESSIMISTIC_READ)).thenReturn(pack);
        when(em.find(ParkingLotEntity.class, 3L)).thenReturn(new ParkingLotEntity());
        when(em.find(VehicleEntity.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(vehicle);
        when(repository.save(any(VehicleSubscriptionEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    private TypedQuery<Long> counts(long drivers, long overlap) {
        TypedQuery<Long> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(drivers, overlap);
        return query;
    }

    @Test void backendFreezesPriceAndComputesThirtyExactDays() {
        counts(1, 0);
        VehicleSubscriptionEntity order = service.register(1L, request);
        assertEquals(150_000L, order.getPriceVnd());
        assertEquals(30L * 86400, java.time.Duration.between(order.getStartsAt(), order.getEndsAt()).getSeconds());
        assertEquals("NEW", order.getRegistrationType());
        assertEquals("PENDING_PAYMENT", order.getStatus());
        assertNull(order.getPaidAt());
        assertEquals(request.getPaymentDeadlineAt(), order.getPaymentDeadlineAt());
    }

    @Test void packageFromAnotherLotFailsBeforeOrderWrite() {
        pack.setParkingLotId(999L);
        assertThrows(ServiceException.class, () -> service.validateRegistration(request, "CAR"));
        verifyNoInteractions(repository);
    }

    @Test void packageForAnotherVehicleTypeFails() {
        assertThrows(ServiceException.class, () -> service.validateRegistration(request, "MOTORBIKE"));
    }

    @Test void disabledOrFuturePackageCannotBeBought() {
        pack.setSaleEnabled(false);
        assertThrows(ServiceException.class, () -> service.validateRegistration(request, "CAR"));
        pack.setSaleEnabled(true); pack.setEffectiveFrom(OffsetDateTime.now().plusDays(1));
        assertThrows(ServiceException.class, () -> service.validateRegistration(request, "CAR"));
    }

    @Test void deadlineIsRequiredAndNeverInvented() {
        request.setPaymentDeadlineAt(null);
        assertThrows(ServiceException.class, () -> service.validateRegistration(request, "CAR"));
        request.setPaymentDeadlineAt(OffsetDateTime.now().minusMinutes(1));
        assertThrows(ServiceException.class, () -> service.validateRegistration(request, "CAR"));
    }

    @Test void absentActiveDriverCannotCreateOrder() {
        counts(0, 0);
        assertThrows(ServiceException.class, () -> service.register(1L, request));
        verify(repository, never()).save(any());
    }

    @Test void renewalCreatesNewOrderWithoutExtendingThePaidPredecessor() {
        counts(1, 0);
        VehicleSubscriptionEntity previous = new VehicleSubscriptionEntity();
        previous.setId(9L); previous.setVehicleId(1L); previous.setParkingLotId(3L);
        previous.setStatus("PAID"); previous.setEndsAt(request.getStartsAt().withOffsetSameInstant(ZoneOffset.UTC));
        request.setPreviousSubscriptionId(9L);
        when(em.find(VehicleSubscriptionEntity.class, 9L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(previous);
        OffsetDateTime oldEnd = previous.getEndsAt();
        VehicleSubscriptionEntity order = service.register(1L, request);
        assertEquals("RENEW", order.getRegistrationType());
        assertEquals(9L, order.getPreviousSubscriptionId());
        assertEquals(oldEnd, previous.getEndsAt());
        assertNotSame(previous, order);
    }

    @Test void renewalCannotUseAnotherVehiclesPaidOrder() {
        counts(1, 0);
        VehicleSubscriptionEntity previous = new VehicleSubscriptionEntity();
        previous.setVehicleId(999L); previous.setParkingLotId(3L); previous.setStatus("PAID");
        previous.setEndsAt(request.getStartsAt());
        request.setPreviousSubscriptionId(9L);
        when(em.find(VehicleSubscriptionEntity.class, 9L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(previous);
        assertThrows(ServiceException.class, () -> service.register(1L, request));
        verify(repository, never()).save(any());
    }

    @Test void paidOverlapPreventsASecondOrderForThePeriod() {
        counts(1, 1);
        assertThrows(ServiceException.class, () -> service.register(1L, request));
        verify(repository, never()).save(any());
    }

    @Test void expiredPeriodIsRejectedEvenWhenPaymentDeadlineIsFuture() {
        counts(1, 0);
        request.setStartsAt(OffsetDateTime.now().withNano(0).minusDays(40));
        assertThrows(ServiceException.class, () -> service.register(1L, request));
        verify(repository, never()).save(any());
    }
}
