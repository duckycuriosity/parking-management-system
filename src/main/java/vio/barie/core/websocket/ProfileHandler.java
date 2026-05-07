package vio.barie.core.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.*;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleUserPermissionRepository;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileHandler {

    private final ParkingCustomerRepository customerRepository;
    private final CameraRepository cameraRepository;
    private final VehicleUserPermissionRepository permissionRepository;

    @Transactional(readOnly = true)
    public void handle(JsonNode event) {
        JsonNode dataNode = event.path("data");
        JsonNode faceNode = dataNode.path("face");

        long cameraId = dataNode.path("cameraId").asLong();
        String cameraUuid = dataNode.path("cameraUUID").asText(null);
        String monitorType = dataNode.path("monitorType").asText(null);
        int monitorId = dataNode.path("monitorId").asInt();

        String profileId = faceNode.path("profileId").asText(null);
        String profileUuid = faceNode.path("profileUUID").asText(null);
        String profileName = faceNode.path("profileName").asText(null);
        boolean isVerified = faceNode.path("isVerified").asBoolean(false);

        // todo: tìm ra camera để sau chỉ định làn + barrier tương ứng
        CameraEntity camera = cameraRepository.findById(cameraId).orElse(null);
        if (camera == null) {
            log.warn("ProfileHandler: unknown camera — cameraId={}, cameraUUID={}", cameraId, cameraUuid);
        }

        if (profileId == null) {
            log.warn("ProfileHandler: face event has no profileID, unable to process : {}", faceNode);
            return;
        }

        ParkingCustomerEntity customer = customerRepository.findByAiIdentityId(profileId).orElse(null);

        if (customer == null) {
            log.info("ProfileHandler: unregistered face appear on sensor-face-unknown-camera  — profileUUID={}, profileName={}, isVerified={}, monitorType={}, monitorId={}, cameraId={}",
                    profileUuid, profileName, isVerified, monitorType, monitorId, cameraId);
            return;
        }

        List<VehicleUserPermissionEntity> permissions =
                permissionRepository.findByParkingCustomerIdWithVehicle(customer.getId());

        if (permissions.isEmpty()) {
            log.warn("ProfileHandler: parking customer id={} has no registered vehicles", customer.getId());
            return;
        }

        List<VehicleEntity> vehicles = permissions.stream()
                .map(VehicleUserPermissionEntity::getVehicle)
                .toList();

        // phần xử lý tiếp theo tong logic làm mịn dữ liệu ai
        vehicles.forEach(vehicle -> {});
    }
}