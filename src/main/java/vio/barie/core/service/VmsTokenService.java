package vio.barie.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import vio.barie.core.config.errorException.ClientException;
import vio.barie.core.dto.vms.VmsAuthRequestDTO;
import vio.barie.core.dto.vms.VmsAuthResponseDTO;
import vio.barie.core.entity.VmsTokenEntity;
import vio.barie.core.repository.VmsTokenRepository;

import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service

@Slf4j
public class VmsTokenService {
    private final VmsTokenRepository tokenRepository;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${vms.tokenName:VMS_TOKEN}")
    private String tokenName;

    @Value("${vms.core.base-url}")
    private String vmsBaseUrl;

    @Value("${vms.core.login-path}")
    private String loginEndpoint;

    @Value("${vms.core.username}")
    private String username;

    @Value("${vms.core.password}")
    private String password;

    public VmsTokenService(
            VmsTokenRepository tokenRepository,
            @Qualifier("vmsRestClient") RestClient restClient,
            ObjectMapper objectMapper
    ) {
        this.tokenRepository = tokenRepository;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public synchronized String getValidToken() {
        VmsTokenEntity tokenEntity = tokenRepository.findByTokenName(tokenName)
                .orElseGet(() -> {
                    VmsTokenEntity newToken = new VmsTokenEntity();
                    newToken.setTokenName(tokenName);
                    return newToken;
                });
        log.info("Using tokenName = {}", tokenName);
        if (tokenEntity.getAccessToken() == null || tokenEntity.getAccessToken().isBlank()) {
            refreshAccessToken(tokenEntity);
        }

        return tokenEntity.getAccessToken();
    }

    public synchronized String forceRefreshToken() {
        VmsTokenEntity tokenEntity = tokenRepository.findByTokenName(tokenName)
                .orElseThrow(() -> new IllegalMonitorStateException("Token config not found in DB"));
        refreshAccessToken(tokenEntity);
        return tokenEntity.getAccessToken();
    }

    public <T> T executeWithAutoLoginRetry(Function<String, T> tokenBasedAction) {
        String token = getValidToken();
        try {
            return tokenBasedAction.apply(token);
        } catch (ClientException ex) {
            if (ex.getStatusCode() == 401 || ex.getStatusCode() == 403) {
                log.warn("Third-party token is invalid or expired. Login again and retry once");
                String newToken = forceRefreshToken();
                return tokenBasedAction.apply(newToken);
            }
            throw ex;

        }
    }

    private void refreshAccessToken(VmsTokenEntity tokenEntity) {
        try {
            VmsAuthRequestDTO requestBody = new VmsAuthRequestDTO();
            requestBody.setUsername(username);
            requestBody.setPassword(password);
            var requestJson = objectMapper.writeValueAsString(requestBody);

            var response = restClient.post()
                    .uri(vmsBaseUrl + loginEndpoint)
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8")
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .body(requestJson)
                    .retrieve()
                    .body(VmsAuthResponseDTO.class);

            if (response != null && response.getToken() != null && !response.getToken().isBlank()) {
                tokenEntity.setAccessToken(response.getToken());
                // chỉ save khi có token
                tokenRepository.save(tokenEntity);
                log.info("Successfully login and updated third-party token");
            } else {
                throw new RuntimeException("Login succeeded but token is null in response");
            }
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("Failed to login to 3rd party system", e);
            throw new RuntimeException("Cannot get authentication token from 3rd party", e);
        }
    }


}
