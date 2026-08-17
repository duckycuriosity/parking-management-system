package vio.barie.core.service.smoothing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.parking.EventImageInfo;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
public class SmoothingAIEvents {
    private static final Logger logger = LoggerFactory.getLogger(SmoothingAIEvents.class);
    private final RedissonClient redissonClient;
    private final ParkingMessageService parkingMessageService;
    private final ObjectMapper objectMapper;
    @Value("${barrier.detection.session-ttl-seconds:210}")
    private long sessionTtlSeconds;

    @Value("${barrier.detection.business-window-ms:180000}")
    private long businessWindowMs;

    @Value("${barrier.detection.burst-gap-ms:8000}")
    private long burstGapMs;

    private static final long LOCK_WAIT_MS = 6000L;
    private static final long LOCK_LEASE_MS = 12000L;

    public void ingestPlate(PendingEventDetection plateDetection) {
        String sessionKey = buildSessionKey(plateDetection);
        RLock lock = redissonClient.getLock(lockKey(sessionKey));
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                logger.warn("ingestPlate: counld not acquire lock key={}", sessionKey);
                return;
            }

            RMap<String, String> session = redissonClient.getMap(sessionKey);
            String lastSeenStr = session.get("lastSeenMs");
            long eventTimeMs = plateDetection.getTimestampMs();
            double confidence = plateDetection.getConfidence() != null ? plateDetection.getConfidence() : 0.0;

            boolean isNewVehicle = lastSeenStr == null || (eventTimeMs - Long.parseLong(lastSeenStr)) > burstGapMs;

            String imageListJson = "[]";
            if (plateDetection.getListImageInfo() != null && !plateDetection.getListImageInfo().isEmpty()) {
                try {
                    imageListJson = objectMapper.writeValueAsString(plateDetection.getListImageInfo());
                } catch (JsonProcessingException e) {
                    logger.error("ingestPlate: failed to serialize image list for plate={}", plateDetection.getPlateNumber(), e);
                }
            }
            if (isNewVehicle) {
                Map<String, String> fresh = new HashMap<>();
                fresh.put("plateNumber", plateDetection.getPlateNumber());
                fresh.put("confidence", String.valueOf(confidence));
                fresh.put("aiPlateId", nullToEmpty(plateDetection.getAiPlateId()));
                fresh.put("firstSeenMs", String.valueOf(eventTimeMs));
                fresh.put("lastSeenMs", String.valueOf(eventTimeMs));
                fresh.put("count", "1");
                fresh.put("imageJson", nullToEmpty(plateDetection.getImage()));
                fresh.put("listImageInfoJson", imageListJson);
                session.clear();
                session.putAll(fresh);
                logger.debug("ingestPlate: new session key={} plate={}", sessionKey, plateDetection.getPlateNumber());
            } else {
                double currentConfidence = parseDoubleSafe(session.get("confidence"));
                session.put("lastSeenMs", String.valueOf(eventTimeMs));
                session.put("count", String.valueOf(parseIntSafe(session.get("count")) + 1));
                if (confidence > currentConfidence) {
                    session.put("plateNumber", plateDetection.getPlateNumber());
                    session.put("confidence", String.valueOf(confidence));
                    session.put("aiPlateId", nullToEmpty(plateDetection.getAiPlateId()));
                    session.put("imageJson", nullToEmpty(plateDetection.getImage()));
                    session.put("listImageInfoJson", imageListJson);
                }
            }
            session.expire(Duration.ofSeconds(sessionTtlSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("ingestPlate: interrupted while locking key={}", sessionKey, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public Optional<PendingEventDetection> ingestFace(PendingEventDetection faceDetection) {
        String sessionKey = buildSessionKey(faceDetection);
        RLock lock = redissonClient.getLock(lockKey(sessionKey));
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                logger.warn("ingestPlate: counld not acquire lock key={}", sessionKey);
                return Optional.empty();
            }

            RMap<String, String> session = redissonClient.getMap(sessionKey);
            Map<String, String> snapshot = session.readAllMap();

            if (snapshot.isEmpty()) {
                logger.debug("face_without_plate_pending key={}", sessionKey);
                return Optional.empty();
            }

            session.delete();

            long lastSeenMs = Long.parseLong(snapshot.get("lastSeenMs"));
            long delta = Math.abs(faceDetection.getTimestampMs() - lastSeenMs);
            if (delta > businessWindowMs) {
                logger.debug("face_session__stale key={} deltaMs={}", sessionKey, delta);
                String barrierId = faceDetection.getDirectionType().equals(DirectionType.EXIT) ? faceDetection.getExitBarrierId() : faceDetection.getEntryBarrierId();
                parkingMessageService.publishDisplayMsg(barrierId, "Face is expired, please scan face again", true, "Scan face again", 8);
                return Optional.empty();
            }

            return Optional.of(mergeFaceWithPlateSession(faceDetection, snapshot));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("ingestPlate: interrupted while locking key={}", sessionKey, e);
            return Optional.empty();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PendingEventDetection mergeFaceWithPlateSession(PendingEventDetection faceDetection, Map<String, String> session) {
        PendingEventDetection merged = new PendingEventDetection();
        merged.setType(DetectionType.FACE);
        merged.setDirectionType(faceDetection.getDirectionType());
        merged.setProfileId(faceDetection.getProfileId());
        merged.setPlateNumber(session.get("plateNumber"));
        merged.setAiPlateId(emptyToNull(session.get("aiPlateId")));
        merged.setConfidence(parseDoubleSafe(session.get("confidence")));
        merged.setTimestampMs(Math.max(faceDetection.getTimestampMs(), Long.parseLong(session.get("lastSeenMs"))));

        merged.setEntryBarrierId(faceDetection.getEntryBarrierId());
        merged.setEntryLaneId(faceDetection.getEntryLaneId());
        merged.setEntryCameraId(faceDetection.getEntryCameraId());
        merged.setExitLaneId(faceDetection.getExitLaneId());
        merged.setExitCameraId(faceDetection.getExitCameraId());
        merged.setExitBarrierId(faceDetection.getExitBarrierId());

        merged.setImage(faceDetection.getImage());
        if (faceDetection.getListImageInfo() != null) {
            merged.getListImageInfo().addAll(faceDetection.getListImageInfo());
        }

        String plateImagesJson = session.get("listImageInfoJson");
        if (plateImagesJson != null && !plateImagesJson.isEmpty() && !plateImagesJson.equals("[]")) {
            try {
                List<EventImageInfo> plateImages = objectMapper.readValue(plateImagesJson, new TypeReference<List<EventImageInfo>>() {
                });
                merged.getListImageInfo().addAll(plateImages);
            } catch (JsonProcessingException e) {
                logger.error("mergeFaceWithPlateSession: failed to deserialize image list from redis", e);
            }
        }
        return merged;
    }

    private String buildSessionKey(PendingEventDetection plateDetection) {
        String lanId = plateDetection.getDirectionType() == DirectionType.ENTRY ? plateDetection.getEntryLaneId().toString() : plateDetection.getExitLaneId().toString();
        String barrierId = plateDetection.getDirectionType() == DirectionType.ENTRY ? plateDetection.getEntryBarrierId() : plateDetection.getExitBarrierId();
        return "detection:pending-plat" + plateDetection.getDirectionType() + ":" + barrierId + ":" + lanId;
    }

    private String lockKey(String sessionKey) {
        return "lock:" + sessionKey;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private double parseDoubleSafe(String value) {
        try {
            return value == null ? 0.0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int parseIntSafe(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
