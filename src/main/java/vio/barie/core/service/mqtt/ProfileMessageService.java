package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.dto.parking.EventImageInfo;
import vio.barie.core.dto.parking.ImageDetail;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.*;
import vio.barie.core.repository.BarrierCameraRepository;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.service.filter.AiEventQualityFilter;
import vio.barie.core.service.filter.QualityCheckResult;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.verification.VerificationRouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileMessageService {

    private final ParkingCustomerRepository customerRepository;
    private final CameraRepository cameraRepository;
    private final BarrierCameraRepository barrierCameraRepository;
    private final ParkingMessageService parkingMessageService;
    private final BarrierRepository barrierRepository;
    private final AiEventQualityFilter aiEventQualityFilter;
    private final VerificationRouter verificationRouter;
    private final static Logger logger = LoggerFactory.getLogger(ProfileMessageService.class);

    @Transactional
    public void handle(JsonNode event) {
        try {
            Long cameraCode = event.path("cameraId").asLong();
            String cameraUUID = event.path("cameraUUID").asText();

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

            Optional<BarrierEntity> barrier = barrierRepository.findBarrierByCameraIdAndRole(cameraInLane.getId(), DetectionType.FACE.toString().toLowerCase());
            if (barrier.isEmpty()) {
                logger.warn("ProfileHandler: unknown barrier — cameraId={} — event={}", cameraUUID, event);
                parkingMessageService.publishErrorWhenHandlingEvent("Barrier not found", cameraUUID, DetectionType.FACE);
                return;
            }

            QualityCheckResult qualityCheckResult = aiEventQualityFilter.evaluateFace(event);

            if (!qualityCheckResult.accepted()) {
                String issues = extractFaceQualityIssues(event);
                String message = "Khong the nhan dien duoc khuon mat"
                        + (issues.isEmpty() ? "" : " - issue: " + issues);
                logger.debug("ProfileHandler: face event rejected - reason={} value ={} cameraUUID={}", qualityCheckResult.reason(), qualityCheckResult.value(), cameraUUID);
                parkingMessageService.publishErrorWhenHandlingEvent(message, cameraUUID, DetectionType.FACE,
                        barrier.get().getBarrierId());
                return;
            }

            Integer profileId = event.path("face").path("profileId").asInt();
            String profileName = event.path("face").path("profileName").asText(null);
            Double confidence = event.path("face").path("confidence").asDouble();
            String imageUrl = event.path("images").path("fullFrame").path("accessUrl").asText();
            logger.debug("ProfileHandler: face event — laneId={}, profileId={}, profileName={}", laneId, profileId,
                    profileName);

            if (!customerRepository.existsByAiProfileId(profileId)) {
                ParkingCustomerEntity newCustomer = new ParkingCustomerEntity();
                UUID uuid = UUID.randomUUID();
                newCustomer.setAiIdentityId("temp_customer" + uuid.toString());
                newCustomer.setAiProfileId(profileId);
                newCustomer.setUsername(profileName);
                newCustomer.setZaloId("null");
                try {
                    customerRepository.save(newCustomer);
                } catch (DataIntegrityViolationException ex) {
                    // Likely a concurrent insert created the same plate; fetch and return existing
                    logger.warn("createNewCustomer: concurrent insert detected for profileId={}, fetching existing", profileId);
                    // rethrow if we cannot recover
                    throw ex;
                }
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

            EventImageInfo faceImgInfo = extractImageInfo(event, "face");
            if (faceImgInfo != null) {
                log.info(">>> Thành công parse lấy ảnh: {}", faceImgInfo);
                pd.getListImageInfo().add(faceImgInfo);
            } else {
                log.error(">>> Lỗi parse JSON: Không tìm thấy trường image Face trong payload!");
            }

            ParkingLotEntity parkingLot = barrier.get().getParkingDevice().getLane().getParkingLot();
            verificationRouter.routeFaceEvent(pd, parkingLot);
        } catch (Exception ex) {
            logger.error("ProfileMessageService: failed to handle profile event — payload={} error={}", event,
                    ex.getMessage(), ex);
            parkingMessageService.publishErrorWhenHandlingEvent(ex.getMessage(), "", DetectionType.FACE);
        }
    }

    private String extractFaceQualityIssues(JsonNode event) {
        JsonNode issues = event.path("face").path("quality").path("issues");
        if (!issues.isArray() || issues.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        issues.forEach(node -> values.add(node.asText()));
        return String.join(", ", values);
    }

    private EventImageInfo extractImageInfo(JsonNode event, String eventType) {
        JsonNode images = event.path("images");
        if (images.isMissingNode()) {
            return null;
        }
        String timestamp = event.path("eventTime").asText(Instant.now().toString());
        List<ImageDetail> details = new ArrayList<>();

        String fullFrameUrl = images.path("fullFrame").path("accessUrl").asText(null);
        if (fullFrameUrl != null) {
            details.add(new ImageDetail(fullFrameUrl, timestamp, "root"));
        }
        String faceCropUrl = images.path("faceCrop").path("accessUrl").asText(null);
        if (faceCropUrl != null) {
            details.add(new ImageDetail(faceCropUrl, timestamp, "crop"));
        }
        if (details.isEmpty()) {
            return null;
        }
        EventImageInfo info = new EventImageInfo();
        info.setEventType(eventType);
        info.setImageInfo(details);
        return info;
    }

}
