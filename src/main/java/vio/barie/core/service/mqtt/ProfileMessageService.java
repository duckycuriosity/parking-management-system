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


@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileMessageService {

    private final ParkingCustomerRepository customerRepository;
    private final CameraRepository cameraRepository;
    private final AiMQTTEventBuffer aiEventBuffer;

    @Transactional()
    public void handle(JsonNode event) {
        String cameraCode = event.path("cameraId").asText();
        String profileId = event.path("profileId").asText(null);
        String profileName = event.path("name").asText(null);

        if (profileId == null) {
            log.warn("ProfileHandler: face event has no profileId — cameraId={}", cameraCode);
            return;
        }

        CameraEntity camera = cameraRepository.findByCodeWithLane(cameraCode).orElse(null);
        if (camera == null) {
            log.warn("ProfileHandler: unknown camera — cameraId={}", cameraCode);
            return;
        }

        long laneId = camera.getLane().getId();
        log.debug("ProfileHandler: face event — laneId={}, profileId={}, profileName={}", laneId, profileId, profileName);

        if(customerRepository.findByAiIdentityId(profileId).isEmpty()){
            ParkingCustomerEntity newCustomer = new ParkingCustomerEntity();
            newCustomer.setAiIdentityId(profileId);
            newCustomer.setUsername(profileName);
            customerRepository.save(newCustomer);
        }

        aiEventBuffer.addFaceEvent(laneId, profileId);
    }
}
