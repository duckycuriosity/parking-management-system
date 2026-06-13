package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.BarrierCameraEntity;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.repository.BarrierCameraRepository;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileMessageService {

    private final ParkingCustomerRepository customerRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;
    private final BarrierCameraRepository barrierCameraRepository;
    private final ParkingMessageService parkingMessageService;
    private final BarrierRepository barrierRepository;
    private final static Logger logger = LoggerFactory.getLogger(ProfileMessageService.class);

    @Transactional
    public void handle(JsonNode event) {
        try {
            Long cameraCode = event.path("cameraId").asLong();
            String cameraUUID = event.path("cameraUUID").asText();
            Integer profileId = event.path("face").path("profileId").asInt();
            String profileName = event.path("face").path("profileName").asText(null);

            if (cameraUUID == null) {
                logger.warn("ProfileHandler: face event has no profileId — cameraId={}", cameraCode);
                parkingMessageService.publishErrorWhenHandlingEvent("Camera UUID is missing in event", "", DetectionType.FACE);
                return;
            }

            CameraEntity aiCamera = cameraRepository.findCameraByUUID(cameraUUID).orElse(null);
            if (aiCamera == null) {
                logger.warn("Camera not found in AI system");
                parkingMessageService.publishErrorWhenHandlingEvent("Cannot found camera corresponding in system", cameraUUID, DetectionType.FACE);
                return;
            }
            CameraEntity cameraInLane = cameraRepository.findByIdWithLane(aiCamera.getId()).orElse(null);
            if (cameraInLane == null) {
                logger.warn("ProfileHandler: unknown camera — cameraId={}", cameraUUID);
                parkingMessageService.publishErrorWhenHandlingEvent("Cannot found camera corresponding to lane", cameraUUID, DetectionType.FACE);
                return;
            }

            long laneId = cameraInLane.getLane().getId();
            logger.debug("ProfileHandler: face event — laneId={}, profileId={}, profileName={}", laneId, profileId,
                    profileName);

            Optional<BarrierEntity> barrier = barrierRepository.findBarrierByCameraIdAndRole(cameraInLane.getId(), DetectionType.FACE.toString().toLowerCase());
            if (barrier.isEmpty()) {
                logger.warn("ProfileHandler: unknown barrier — cameraId={} — event={}", cameraUUID, event);
                parkingMessageService.publishErrorWhenHandlingEvent("Barrier not found", cameraUUID, DetectionType.FACE);
                return;
            }

            if (!customerRepository.existsByAiProfileId(profileId)) {
                ParkingCustomerEntity newCustomer = new ParkingCustomerEntity();
                UUID uuid = UUID.randomUUID();
                newCustomer.setAiIdentityId("temp_customer" + uuid.toString());
                newCustomer.setAiProfileId(profileId);
                newCustomer.setUsername(profileName);
                newCustomer.setZaloId("null");
                customerRepository.save(newCustomer);
            }

            PendingEventDetection pd = new PendingEventDetection();
            pd.setType(DetectionType.FACE);
            pd.setProfileId(profileId);

            DirectionType barrierDirection = barrier.map(BarrierEntity::getDirection).orElse(null);

            if (barrierDirection == DirectionType.ENTRY) {
                pd.setEntryLaneId(laneId);
                pd.setEntryBarrierId(barrier.map(BarrierEntity::getBarrierId).orElse(null));
                pd.setEntryCameraId(cameraInLane.getId());
                pd.setDirectionType(DirectionType.ENTRY);
            } else if (barrierDirection == DirectionType.EXIT) {
                pd.setExitBarrierId(barrier.map(BarrierEntity::getBarrierId).orElse(null));
                pd.setExitCameraId(cameraInLane.getId());
                pd.setExitLaneId(laneId);
                pd.setDirectionType(DirectionType.EXIT);
            } else {
                logger.warn("ProfileHandler: unknown barrier direction — cameraId={} direction={} event={}", cameraUUID, barrierDirection, event);
                parkingMessageService.publishErrorWhenHandlingEvent("Unknown barrier direction", cameraUUID, DetectionType.FACE);
                return;
            }
            pd.setTimestampMs(System.currentTimeMillis());

            aiEventBuffer.addFaceEvent(pd);
        } catch (Exception ex) {
            logger.error("ProfileMessageService: failed to handle profile event — payload={} error={}", event,
                    ex.getMessage(), ex);
            parkingMessageService.publishErrorWhenHandlingEvent(ex.getMessage(), "", DetectionType.FACE);
        }
    }
}
