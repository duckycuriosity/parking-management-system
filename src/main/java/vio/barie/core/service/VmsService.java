package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vio.barie.core.config.VmsProperties;
import vio.barie.core.entity.VmsEventEntity;
import vio.barie.core.entity.VmsTokenEntity;
import vio.barie.core.exception.BusinessException;
import vio.barie.core.repository.VmsEventRepository;
import vio.barie.core.repository.VmsTokenRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class VmsService {

    private static final Duration EVENT_LOOKBACK_WINDOW = Duration.ofMinutes(5);
    private static final List<String> RESPONSE_ARRAY_FIELDS = List.of(
            "data",
            "items",
            "result",
            "records",
            "rows",
            "content");

    private final RestClient restClient;
    private final VmsTokenRepository vmsTokenRepository;
    private final VmsEventRepository vmsEventRepository;
    private final VmsProperties vmsProperties;
    private final ObjectMapper objectMapper;

    public VmsService(
            @Qualifier("vmsRestClient") RestClient restClient,
            VmsTokenRepository vmsTokenRepository,
            VmsEventRepository vmsEventRepository,
            VmsProperties vmsProperties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.vmsTokenRepository = vmsTokenRepository;
        this.vmsEventRepository = vmsEventRepository;
        this.vmsProperties = vmsProperties;
        this.objectMapper = objectMapper;
    }

    public JsonNode fetchLatestRecord() {
        String accessToken = getOrRefreshAccessToken();

        try {
            return fetchLatestRecordWithToken(accessToken);
        } catch (RestClientResponseException ex) {
            if (isUnauthorized(ex)) {
                String refreshedToken = refreshAccessToken();
                return fetchLatestRecordWithToken(refreshedToken);
            }
            throw new BusinessException(
                    "Failed to fetch VMS data: " + ex.getResponseBodyAsString(),
                    "VMS_FETCH_FAILED",
                    ex.getStatusCode().value());
        }
    }

    public Optional<VmsEventEntity> fetchAndStoreLatestEventIfRecent() {
        JsonNode event = fetchLatestRecord();

        Optional<Instant> eventInstantOpt = extractEventInstant(event);
        if (eventInstantOpt.isEmpty()) {
            log.warn("Skip VMS event because field 'time' is missing or invalid: {}", event);
            return Optional.empty();
        }

        Instant eventInstant = eventInstantOpt.get();
        if (eventInstant.isBefore(Instant.now().minus(EVENT_LOOKBACK_WINDOW))) {
            return Optional.empty();
        }

        Long eventId = extractEventId(event);
        if (eventId == null) {
            log.warn("Skip VMS event because field 'id' is missing or invalid: {}", event);
            return Optional.empty();
        }

        if (vmsEventRepository.existsByVmsEventId(eventId)) {
            return Optional.empty();
        }

        VmsEventEntity entity = mapEventEntity(event, eventId, eventInstant);
        return Optional.of(vmsEventRepository.save(entity));
    }

    private JsonNode fetchLatestRecordWithToken(String accessToken) {
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(vmsProperties.getDataPath())
                        .queryParam("order", vmsProperties.getDataOrder())
                        .queryParam("isConfirmed", vmsProperties.getDataIsConfirmed())
                        .queryParam("count", vmsProperties.getDataCount())
                        .queryParam("type", vmsProperties.getDataType())
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .header("Referer", vmsProperties.getDataReferer())
                .header("Origin", vmsProperties.getOrigin())
                .retrieve()
                .body(JsonNode.class);

        return extractLatestRecord(response);
    }

    private String getOrRefreshAccessToken() {
        return vmsTokenRepository.findByTokenName(vmsProperties.getTokenName())
                .map(VmsTokenEntity::getAccessToken)
                .filter(StringUtils::hasText)
                .orElseGet(this::refreshAccessToken);
    }

    private String refreshAccessToken() {
        return loginAndStoreToken();
    }

    private String loginAndStoreToken() {
        String loginPayload = buildLoginPayload();

        String responseBody = restClient.post()
                .uri(vmsProperties.getLoginPath())
                .contentType(MediaType.TEXT_PLAIN)
                .header("Origin", vmsProperties.getOrigin())
                .header("Referer", vmsProperties.getLoginReferer())
                .body(loginPayload)
                .retrieve()
                .body(String.class);

        String accessToken = extractAccessToken(responseBody);
        storeToken(accessToken);
        return accessToken;
    }

    private void storeToken(String accessToken) {
        VmsTokenEntity tokenEntity = vmsTokenRepository.findByTokenName(vmsProperties.getTokenName())
                .orElseGet(VmsTokenEntity::new);
        tokenEntity.setTokenName(vmsProperties.getTokenName());
        tokenEntity.setAccessToken(accessToken);
        vmsTokenRepository.save(tokenEntity);
    }

    private String buildLoginPayload() {
        try {
            return objectMapper
                    .writeValueAsString(new LoginRequest(vmsProperties.getUsername(), vmsProperties.getPassword()));
        } catch (Exception ex) {
            throw new BusinessException(
                    "Unable to build VMS login payload",
                    "VMS_LOGIN_PAYLOAD_ERROR",
                    500);
        }
    }

    private String extractAccessToken(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new BusinessException(
                    "VMS login returned an empty token",
                    "VMS_LOGIN_EMPTY_TOKEN",
                    502);
        }

        String trimmed = responseBody.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                String token = firstTextValue(root, "token", "accessToken", "access_token", "data", "result", "value");
                if (StringUtils.hasText(token)) {
                    return token.trim();
                }
            } catch (Exception ignored) {
                // Fall through to plain-text handling.
            }
        }

        String normalized = trimmed;
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(
                    "VMS login returned an invalid token",
                    "VMS_LOGIN_INVALID_TOKEN",
                    502);
        }

        return normalized;
    }

    private JsonNode extractLatestRecord(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new BusinessException(
                    "VMS data response is empty",
                    "VMS_DATA_EMPTY",
                    502);
        }

        if (response.isArray()) {
            return firstArrayElement(response);
        }

        for (String fieldName : RESPONSE_ARRAY_FIELDS) {
            JsonNode candidate = response.get(fieldName);
            if (candidate == null || candidate.isNull()) {
                continue;
            }
            if (candidate.isArray()) {
                return firstArrayElement(candidate);
            }
            if (candidate.isObject()) {
                return candidate;
            }
        }

        return response;
    }

    private JsonNode firstArrayElement(JsonNode arrayNode) {
        if (arrayNode.isEmpty()) {
            throw new BusinessException(
                    "VMS data list is empty",
                    "VMS_DATA_EMPTY",
                    404);
        }
        return arrayNode.elements().next();
    }

    private boolean isUnauthorized(RestClientResponseException ex) {
        int statusCode = ex.getStatusCode().value();
        return statusCode == 401 || statusCode == 403;
    }

    private String firstTextValue(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode candidate = root.findValue(fieldName);
            if (candidate != null && candidate.isTextual() && StringUtils.hasText(candidate.asText())) {
                return candidate.asText();
            }
        }
        return null;
    }

    private Optional<Instant> extractEventInstant(JsonNode event) {
        JsonNode timeNode = event.get("time");
        if (timeNode == null || !timeNode.isNumber()) {
            return Optional.empty();
        }

        double epochSeconds = timeNode.asDouble();
        long seconds = (long) epochSeconds;
        long nanos = Math.round((epochSeconds - seconds) * 1_000_000_000L);
        if (nanos == 1_000_000_000L) {
            seconds += 1;
            nanos = 0;
        }

        return Optional.of(Instant.ofEpochSecond(seconds, nanos));
    }

    private Long extractEventId(JsonNode event) {
        JsonNode idNode = event.get("id");
        if (idNode == null || !idNode.isNumber()) {
            return null;
        }
        return idNode.asLong();
    }

    private VmsEventEntity mapEventEntity(JsonNode event, Long eventId, Instant eventInstant) {
        JsonNode dataNode = event.path("data");
        JsonNode faceNode = dataNode.path("face");

        VmsEventEntity entity = new VmsEventEntity();
        entity.setVmsEventId(eventId);
        if (event.path("serviceId").isNumber()) {
            entity.setServiceId(event.path("serviceId").asInt());
        }
        if (event.path("originId").isNumber()) {
            entity.setOriginId(event.path("originId").asLong());
        }
        entity.setEventType(event.path("type").asText(null));
        if (!event.path("isConfirmed").isMissingNode()) {
            entity.setIsConfirmed(event.path("isConfirmed").asBoolean());
        }
        entity.setEventTime(LocalDateTime.ofInstant(eventInstant, ZoneOffset.UTC));
        entity.setEventEpochSeconds(event.path("time").asDouble());
        entity.setCameraUuid(dataNode.path("cameraUUID").asText(null));
        entity.setMonitorType(dataNode.path("monitorType").asText(null));
        entity.setProfileUuid(faceNode.path("profileUUID").asText(null));
        entity.setProfileName(faceNode.path("profileName").asText(null));
        entity.setPayload(event.toString());
        return entity;
    }

    private record LoginRequest(String username, String password) {
    }
}
