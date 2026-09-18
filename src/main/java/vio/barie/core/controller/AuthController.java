package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    @Value("${keycloak.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.auth-client-id:barie-client-public}")
    private String clientId;

    @Value("${keycloak.auth-client-secret:}")
    private String clientSecret;

    private final RestTemplate restTemplate;

    /**
     * Get access token from Keycloak
     * Request: POST /api/auth/token
     * Body: 
     *   - username (form-data)
     *   - password (form-data)
     *
     * Example:
     * curl -X POST http://localhost:8080/api/auth/token \
     *   -d "username=test_user&password=password123"
     */
    @PostMapping("/token")
    public ResponseEntity<?> getToken(@RequestParam String username,
                                      @RequestParam String password) {
        try {
            String tokenUrl = keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";

            // Build request body
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("grant_type", "password");
            body.add("username", username);
            body.add("password", password);

            // If client has secret, add it
            if (clientSecret != null && !clientSecret.isEmpty()) {
                body.add("client_secret", clientSecret);
            }

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            log.info("Requesting token from Keycloak: {} for user: {}", tokenUrl, username);
            ResponseEntity<Object> response = restTemplate.postForEntity(tokenUrl, request, Object.class);

            log.info("Token request successful for user: {}", username);
            return response;

        } catch (Exception ex) {
            log.error("Failed to get token for user: {} — error: {}", username, ex.getMessage(), ex);
            return ResponseEntity.status(401).body(new ErrorResponse("Failed to authenticate", ex.getMessage()));
        }
    }

    /**
     * Refresh access token using refresh token
     * Request: POST /api/auth/refresh
     * Body:
     *   - refresh_token (form-data)
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestParam("refresh_token") String refreshToken) {
        try {
            String tokenUrl = keycloakServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", refreshToken);

            if (clientSecret != null && !clientSecret.isEmpty()) {
                body.add("client_secret", clientSecret);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            log.info("Refreshing token from Keycloak: {}", tokenUrl);
            ResponseEntity<Object> response = restTemplate.postForEntity(tokenUrl, request, Object.class);

            log.info("Token refresh successful");
            return response;

        } catch (Exception ex) {
            log.error("Failed to refresh token — error: {}", ex.getMessage(), ex);
            return ResponseEntity.status(401).body(new ErrorResponse("Failed to refresh token", ex.getMessage()));
        }
    }

    static class ErrorResponse {
        public String error;
        public String message;

        public ErrorResponse(String error, String message) {
            this.error = error;
            this.message = message;
        }

        public String getError() {
            return error;
        }

        public String getMessage() {
            return message;
        }
    }
}
