package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingCustomerRepository;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileMessageService {

    private final ParkingCustomerRepository customerRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;

    @Transactional()
    public void handle(JsonNode event) {
        Long cameraCode = event.path("cameraId").asLong();
        Integer profileId = event.path("profileId").asInt();
        String profileName = event.path("name").asText(null);

        if (profileId == null) {
            log.warn("ProfileHandler: face event has no profileId — cameraId={}", cameraCode);
            return;
        }

        CameraEntity aiCamera = cameraRepository.findCameraByAiCameraId(cameraCode).orElse(null);
        if (aiCamera == null) {
            log.warn("Camera not found in AI system");
            return;
        }
        CameraEntity cameraInLane = cameraRepository.findByIdWithLane(aiCamera.getId()).orElse(null);
        if (cameraInLane == null) {
            log.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
            return;
        }

        long laneId = cameraInLane.getLane().getId();
        log.debug("ProfileHandler: face event — laneId={}, profileId={}, profileName={}", laneId, profileId,
                profileName);

        if (customerRepository.findByAiProfileId(profileId).isEmpty()) {
            ParkingCustomerEntity newCustomer = new ParkingCustomerEntity();
            UUID uuid = UUID.randomUUID();
            newCustomer.setAiIdentityId("temp_customer" + uuid.toString());
            newCustomer.setAiProfileId(profileId);
            newCustomer.setUsername(profileName);
            customerRepository.save(newCustomer);
        }

        aiEventBuffer.addFaceEvent(cameraInLane.getId(), laneId, profileId);
    }
}
