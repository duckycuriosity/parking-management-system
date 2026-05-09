package vio.barie.core.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingCustomerRepository;


@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileHandler {

    private final ParkingCustomerRepository customerRepository;
    private final CameraRepository cameraRepository;
    private final AiEventBuffer aiEventBuffer;

    @Transactional(readOnly = true)
    public void handle(JsonNode event) {
        JsonNode dataNode = event.path("data");
        JsonNode faceNode = dataNode.path("face");

        long cameraId = dataNode.path("cameraId").asLong();
        String cameraUuid = dataNode.path("cameraUUID").asText(null);

        String profileId = faceNode.path("profileId").asText(null);
        String profileUuid = faceNode.path("profileUUID").asText(null);
        String profileName = faceNode.path("profileName").asText(null);
        boolean isVerified = faceNode.path("isVerified").asBoolean(false);

        if (profileId == null) {
            log.warn("ProfileHandler: face event has no profileId — cameraId={}", cameraId);
            return;
        }

        ParkingCustomerEntity customer = customerRepository.findByAiIdentityId(profileId).orElse(null);
        if (customer == null) {
            log.info("ProfileHandler: unregistered face — profileUUID={}, profileName={}, isVerified={}, cameraId={}",
                    profileUuid, profileName, isVerified, cameraId);
            return;
        }

        CameraEntity camera = cameraRepository.findByIdWithLane(cameraId).orElse(null);
        if (camera == null) {
            log.warn("ProfileHandler: unknown camera — cameraId={}, cameraUUID={}", cameraId, cameraUuid);
            return;
        }

        long laneId = camera.getLane().getId();
        log.debug("ProfileHandler: face event — laneId={}, profileId={}, profileName={}", laneId, profileId, profileName);

        if(customerRepository.findByAiIdentityId(profileId).isEmpty()){
            ParkingCustomerEntity newCustomer = new ParkingCustomerEntity();
            newCustomer.setAiIdentityId(profileId);
            customerRepository.save(newCustomer);
        }

        aiEventBuffer.addFaceEvent(laneId, profileId);
    }
}
