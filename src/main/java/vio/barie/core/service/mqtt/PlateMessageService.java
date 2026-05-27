package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlateMessageService {

    // setup logger with class name
    private static final Logger logger = LoggerFactory.getLogger(PlateMessageService.class);

    private final VehicleRepository vehicleRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;
    private final TicketRepository ticketRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingMessageService parkingMessageService;
    private final BarrierRepository barrierRepository;
    private final DetectionMatchingService detectionMatchingService;
    private final UUID uuid = UUID.randomUUID();

    public void handle(JsonNode event) {
        try {
            Long cameraCode = event.path("cameraId").asLong();
            String plateNumber = event.path("plate").path("plate").asText(null);
            String cameraUUID = event.path("cameraUUID").asText();
            Boolean isVerified = event.path("plate").path("isVerified").asBoolean(false);

            if (plateNumber == null || cameraUUID == null) {
                logger.warn("PlateHandler: plate event has no plateNumber — cameraId={}", cameraCode);
                return;
            }

            CameraEntity aiCamera = cameraRepository.findCameraByUUID(cameraUUID).orElse(null);
            if (aiCamera == null) {
                logger.warn("Camera not found in AI system");
                parkingMessageService.publishErrorWhenHandlingEvent("Camera not found", cameraUUID,
                        DetectionType.PLATE);
                return;
            }
            CameraEntity cameraInLane = cameraRepository.findByIdWithLane(aiCamera.getId()).orElse(null);
            if (cameraInLane == null) {
                logger.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
                parkingMessageService.publishErrorWhenHandlingEvent("Camera not found", cameraUUID,
                        DetectionType.PLATE);
                return;
            }

            long laneId = cameraInLane.getLane().getId();
            logger.debug("PlateHandler: plate={} on laneId={}", plateNumber, laneId);

            Optional<BarrierEntity> barrier = barrierRepository.findBarrierByCameraIdAndRole(cameraInLane.getId(),
                    DetectionType.PLATE.toString().toLowerCase());
            if (barrier.isEmpty()) {
                parkingMessageService.publishErrorWhenHandlingEvent("Camera does not match any barrier", cameraUUID,
                        DetectionType.PLATE);
                logger.error("Camera does not match any barrier");
                return;
            }

            DirectionType direction = barrier.get().getDirection();
            VehicleEntity vehicle = vehicleRepository.findPlateByPlateNumber(plateNumber);

            // If vehicle is registered with active ticket, handle as registered customer
            if (vehicle != null && ticketRepository.existsByVehicleId(vehicle.getId())) {
                handleRegisteredVehicle(vehicle, plateNumber, cameraInLane, barrier.get(), laneId, direction,
                        cameraUUID);
            } else {
                // Otherwise handle as guest/unregistered vehicle
                handleUnregisteredVehicle(plateNumber, cameraInLane, barrier.get(), laneId, direction);
            }

        } catch (Exception ex) {
            log.error("PlateMessageService: failed to handle plate event — payload={} error={}", event, ex.getMessage(),
                    ex);
        }
    }

    /**
     * Handle plate event for registered customer (has active ticket)
     * For ENTRY: immediately process without waiting for face match
     * For EXIT: buffer event and wait for face match to verify identity
     */
    private void handleRegisteredVehicle(VehicleEntity vehicle, String plateNumber,
                                         CameraEntity cameraInLane, BarrierEntity barrier,
                                         long laneId, DirectionType direction, String cameraUUID) {
        try {
            Optional<TicketEntity> ticket = ticketRepository.findTicketByVehicleId(vehicle.getId());
            if (ticket.isEmpty()) {
                parkingMessageService.publishErrorWhenHandlingEvent(
                        "Cannot find ticket for registered vehicle", cameraUUID, DetectionType.PLATE);
                logger.warn("No ticket found for registered vehicle: plate={}", plateNumber);
                return;
            }

            ParkingCustomerEntity customer = parkingCustomerRepository
                    .findCustomerById(ticket.get().getCustomerId());
            if (customer == null) {
                parkingMessageService.publishErrorWhenHandlingEvent(
                        "Customer not found for ticket", cameraUUID, DetectionType.PLATE);
                logger.warn("Customer not found for vehicle: plate={}", plateNumber);
                return;
            }

            PendingEventDetection event = buildPendingEventForRegistered(
                    vehicle, customer, plateNumber, cameraInLane, barrier, laneId, direction);

            if (direction == DirectionType.ENTRY) {
                // For registered customers at entry: immediately process without face matching
                logger.info("ENTRY registered vehicle plate={} customer={} — processing immediately",
                        plateNumber, customer.getId());
                detectionMatchingService.handleEntryEvent(event);
            } else if (direction == DirectionType.EXIT) {
                // For registered customers at exit: buffer event and wait for face match
                // to verify it's the same person who entered
                logger.info("EXIT registered vehicle plate={} customer={} — buffering for face match",
                        plateNumber, customer.getId());
                aiEventBuffer.addPlateEvent(event);
            }

        } catch (Exception ex) {
            logger.error("handleRegisteredVehicle: failed to process registered vehicle — plate={} error={}",
                    plateNumber, ex.getMessage(), ex);
        }
    }

    /**
     * Handle plate event for unregistered/guest vehicle
     * Always buffer event and wait for face matching logic to create session
     */
    private void handleUnregisteredVehicle(String plateNumber, CameraEntity cameraInLane,
                                           BarrierEntity barrier, long laneId, DirectionType direction) {
        try {
            if (!vehicleRepository.existsByPlateNumber(plateNumber)) {
                logger.info("Vehicle with plate={} exists but no active ticket — treating as unregistered",
                        plateNumber);
                VehicleEntity newVehicle = createNewVehicle(plateNumber);
            }

            PendingEventDetection event = buildPendingEventForUnregistered(
                    plateNumber, cameraInLane, barrier, laneId, direction);

            if (direction == DirectionType.ENTRY) {
                // Notify entry of unregistered vehicle
                parkingMessageService.publishEntryVehicleInfo(barrier.getBarrierId(), plateNumber, LocalDateTime.now());
                logger.info("ENTRY unregistered vehicle plate={} — buffering for face + plate matching", plateNumber);
            } else if (direction == DirectionType.EXIT) {
                logger.info("EXIT unregistered vehicle plate={} — buffering for face + plate matching", plateNumber);
            }

            aiEventBuffer.addPlateEvent(event);

        } catch (Exception ex) {
            logger.error("handleUnregisteredVehicle: failed to process unregistered vehicle — plate={} error={}",
                    plateNumber, ex.getMessage(), ex);
        }
    }

    /**
     * Create PendingEventDetection for registered customer (has ticket)
     */
    private PendingEventDetection buildPendingEventForRegistered(VehicleEntity vehicle,
                                                                 ParkingCustomerEntity customer,
                                                                 String plateNumber,
                                                                 CameraEntity camera,
                                                                 BarrierEntity barrier,
                                                                 long laneId,
                                                                 DirectionType direction) {
        PendingEventDetection event = new PendingEventDetection();
        event.setPlateNumber(plateNumber);
        // aiIdentityPlateId can be a numeric id or UUID string from AI; keep as String
        event.setAiPlateId(vehicle.getAiIdentityPlateId());
        event.setType(DetectionType.PLATE);
        event.setTimestampMs(System.currentTimeMillis());
        event.setProfileId(customer.getAiProfileId());
        event.setDirectionType(direction);

        if (direction == DirectionType.ENTRY) {
            event.setEntryBarrierId(barrier.getBarrierId());
            event.setEntryCameraId(camera.getId());
            event.setEntryLaneId(laneId);
        } else {
            event.setExitBarrierId(barrier.getBarrierId());
            event.setExitCameraId(camera.getId());
            event.setExitLaneId(laneId);
        }

        return event;
    }

    /**
     * Create PendingEventDetection for unregistered vehicle (guest)
     */
    private PendingEventDetection buildPendingEventForUnregistered(String plateNumber,
                                                                   CameraEntity camera,
                                                                   BarrierEntity barrier,
                                                                   long laneId,
                                                                   DirectionType direction) {
        PendingEventDetection event = new PendingEventDetection();
        event.setType(DetectionType.PLATE);
        event.setPlateNumber(plateNumber);
        event.setTimestampMs(System.currentTimeMillis());
        event.setDirectionType(direction);

        if (direction == DirectionType.ENTRY) {
            event.setEntryBarrierId(barrier.getBarrierId());
            event.setEntryCameraId(camera.getId());
            event.setEntryLaneId(laneId);
        } else {
            event.setExitBarrierId(barrier.getBarrierId());
            event.setExitCameraId(camera.getId());
            event.setExitLaneId(laneId);
        }

        return event;
    }

    /**
     * Create new vehicle record for unregistered vehicle with temporary AI identity
     */
    private VehicleEntity createNewVehicle(String plateNumber) {
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setPlateNumber(plateNumber);
        vehicle.setAiIdentityPlateId("UNVERIFIED_" + UUID.randomUUID().toString());
        vehicle.setVehicleType("OTHER");
        try {
            return vehicleRepository.save(vehicle);
        } catch (DataIntegrityViolationException ex) {
            // Likely a concurrent insert created the same plate; fetch and return existing
            logger.warn("createNewVehicle: concurrent insert detected for plate={}, fetching existing", plateNumber);
            VehicleEntity existing = vehicleRepository.findPlateByPlateNumber(plateNumber);
            if (existing != null) {
                return existing;
            }
            // rethrow if we cannot recover
            throw ex;
        }
    }
}
