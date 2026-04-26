package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vio.barie.core.config.VmsProperties;
import vio.barie.core.entity.VmsTokenEntity;
import vio.barie.core.exception.BusinessException;
import vio.barie.core.repository.VmsTokenRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VmsService {

    private static final List<String> RESPONSE_ARRAY_FIELDS = List.of(
            "data",
            "items",
            "result",
            "records",
            "rows",
            "content");

    private final RestClient restClient;
    private final VmsTokenRepository vmsTokenRepository;
    private final VmsProperties vmsProperties;
    private final ObjectMapper objectMapper;

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

    private record LoginRequest(String username, String password) {
    }
}
