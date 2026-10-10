package vio.barie.core.verification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.VehicleCustomerEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.OwnerAccountRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleCustomerRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.service.LaneIncidentService;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.smoothing.SmoothingAIEvents;
import vio.barie.core.verification.strategy.FacePlateVerificationService;
import vio.barie.core.utils.DetectionType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerVehicleEntryAuthorizationTest {
    private OwnerAccountRepository owners;
    private VehicleCustomerRepository relations;
    private OwnerVehicleEntryAuthorization authorization;
    private VehicleEntity vehicle;
    private ParkingCustomerEntity customer;
    private OwnerAccountEntity owner;
    private VehicleCustomerEntity relation;

    @BeforeEach
    void setup() {
        owners = mock(OwnerAccountRepository.class);
        relations = mock(VehicleCustomerRepository.class);
        authorization = new OwnerVehicleEntryAuthorization(owners, relations);
        vehicle = new VehicleEntity();
        vehicle.setId(11L);
        vehicle.setOwnerId(1L);
        vehicle.setStatus("ACTIVE");
        vehicle.setPlateNumber("30A12345");
        customer = new ParkingCustomerEntity();
        customer.setId(22L);
        customer.setOwnerId(1L);
        customer.setStatus("ACTIVE");
        customer.setAiProfileId(33);
        customer.setAiSyncState("SYNCED");
        owner = new OwnerAccountEntity();
        owner.setId(1L);
        owner.setStatus("ACTIVE");
        relation = new VehicleCustomerEntity();
        relation.setVehicleId(11L);
        relation.setCustomerId(22L);
        relation.setStatus("ACTIVE");
        when(owners.findById(1L)).thenReturn(Optional.of(owner));
        when(relations.findByVehicleIdAndCustomerId(11L, 22L)).thenReturn(Optional.of(relation));
    }

    @Test
    void activeExplicitGrantPermitsPmsEntryWithoutCheckingPayment() {
        assertThat(authorization.allows(vehicle, customer)).isTrue();
    }

    @Test
    void revokedAndMissingGrantsDenyEntry() {
        relation.setStatus("REVOKED");
        assertThat(authorization.allows(vehicle, customer)).isFalse();
        when(relations.findByVehicleIdAndCustomerId(11L, 22L)).thenReturn(Optional.empty());
        assertThat(authorization.allows(vehicle, customer)).isFalse();
    }

    @Test
    void crossOwnerPersonIsNeverAuthorizedByAnOtherwiseExistingGrant() {
        customer.setOwnerId(2L);
        assertThat(authorization.allows(vehicle, customer)).isFalse();
        verifyNoInteractions(relations);
    }

    @Test
    void inactivePersonVehicleOrOwnerEachDenyEntry() {
        customer.setStatus("INACTIVE");
        assertThat(authorization.allows(vehicle, customer)).isFalse();
        customer.setStatus("ACTIVE");
        vehicle.setStatus("INACTIVE");
        assertThat(authorization.allows(vehicle, customer)).isFalse();
        vehicle.setStatus("ACTIVE");
        owner.setStatus("INACTIVE");
        assertThat(authorization.allows(vehicle, customer)).isFalse();
    }

    @Test
    void ownerlessLegacyVehiclesRetainTheirPriorEntryBehavior() {
        vehicle.setOwnerId(null);
        customer.setStatus("INACTIVE");
        assertThat(authorization.allows(vehicle, customer)).isTrue();
        verifyNoInteractions(owners, relations);
    }

    @Test
    void realEntryHookBlocksSessionBeforeBarrierLookupForRevokedGrant() {
        relation.setStatus("REVOKED");
        VehicleRepository vehicles = mock(VehicleRepository.class);
        ParkingCustomerRepository customers = mock(ParkingCustomerRepository.class);
        BarrierRepository barriers = mock(BarrierRepository.class);
        ParkingMessageService messages = mock(ParkingMessageService.class);
        ParkingSessionLifecycleService sessions = mock(ParkingSessionLifecycleService.class);
        when(vehicles.findPlateByPlateNumber("30A12345")).thenReturn(vehicle);
        when(customers.findByAiProfileId(33)).thenReturn(Optional.of(customer));
        var strategy = new FacePlateVerificationService(barriers, vehicles, customers, messages,
                mock(SmoothingAIEvents.class), sessions, mock(LaneIncidentService.class), authorization);
        PendingEventDetection detection = new PendingEventDetection();
        detection.setPlateNumber("30A12345");
        detection.setProfileId(33);
        detection.setType(DetectionType.FACE);
        detection.setEntryBarrierId("entry-barrier");
        strategy.handleEntryEvent(detection);
        verifyNoInteractions(sessions, barriers);
        verify(messages).publishErrorWhenHandlingEvent(anyString(), eq(""), eq(DetectionType.FACE),
                eq("entry-barrier"), eq("30A12345"), eq(33), eq("VEHICLE_USER_NOT_AUTHORIZED"), isNull());
    }

    @Test
    void exitDoesNotApplyTheNewEntryPermissionGate() {
        VehicleRepository vehicles = mock(VehicleRepository.class);
        ParkingCustomerRepository customers = mock(ParkingCustomerRepository.class);
        OwnerVehicleEntryAuthorization entryOnly = mock(OwnerVehicleEntryAuthorization.class);
        BarrierRepository barriers = mock(BarrierRepository.class);
        ParkingSessionLifecycleService sessions = mock(ParkingSessionLifecycleService.class);
        customer.setStatus("INACTIVE");
        relation.setStatus("REVOKED");
        ParkingSessionEntity openSession = new ParkingSessionEntity();
        openSession.setEntryCustomer(customer);
        when(sessions.findOpenSessionForVehicle(11L)).thenReturn(Optional.of(openSession));
        ParkingDeviceEntity device = new ParkingDeviceEntity();
        device.setLane(new LaneEntity());
        BarrierEntity exitBarrier = new BarrierEntity();
        exitBarrier.setParkingDevice(device);
        when(barriers.findByBarrierIdWithDeviceAndLaneAndParkingLot("exit-barrier")).thenReturn(Optional.of(exitBarrier));
        when(vehicles.findPlateByPlateNumber("30A12345")).thenReturn(vehicle);
        when(customers.findByAiProfileId(33)).thenReturn(Optional.of(customer));
        var strategy = new FacePlateVerificationService(barriers, vehicles, customers,
                mock(ParkingMessageService.class), mock(SmoothingAIEvents.class),
                sessions, mock(LaneIncidentService.class), entryOnly);
        PendingEventDetection detection = new PendingEventDetection();
        detection.setPlateNumber("30A12345");
        detection.setProfileId(33);
        detection.setExitBarrierId("exit-barrier");
        detection.setExitLaneId(77L);
        strategy.handleExitEvent(detection);
        verifyNoInteractions(entryOnly);
        verify(sessions).closeExit(openSession, customer, exitBarrier, detection);
    }
}
