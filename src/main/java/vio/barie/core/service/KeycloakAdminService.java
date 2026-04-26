package vio.barie.core.service;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vio.barie.core.config.exception.ClientException;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KeycloakAdminService {

    private final Keycloak keycloak;

    @Value("${keycloak.realm}")
    private String realm;

    public String createUser(String username, String email, String rawPassword, Map<String, List<String>> attributes) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(true);
        if (attributes != null && !attributes.isEmpty()) {
            user.setAttributes(attributes);
        }

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(rawPassword);
        credential.setTemporary(false);

        user.setCredentials(Collections.singletonList(credential));

        try (Response response = keycloak.realm(realm).users().create(user)) {
            int status = response.getStatus();
            if (status == 201) {
                return CreatedResponseUtil.getCreatedId(response);
            }
            if (status == 409) {
                throw new ClientException("User already exists in Keycloak", 409);
            }
            String body = response.hasEntity() ? response.readEntity(String.class) : "";
            throw new ClientException("create user failed:"+ body, 409);
        }
    }

    // Cập nhật attributes (role, orgId...)
    public void updateUserAttributes(String keycloakUserId, Map<String, List<String>> attrs) {
        UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);
        UserRepresentation user = userResource.toRepresentation();
        Map<String, List<String>> existing = user.getAttributes();
        if (existing == null) existing = new HashMap<>();
        existing.putAll(attrs);
        user.setAttributes(existing);
        userResource.update(user);
    }

    // Enable / disable user
    public void setUserEnabled(String keycloakUserId, boolean enabled) {
        UserResource userResource = keycloak.realm(realm).users().get(keycloakUserId);
        UserRepresentation user = userResource.toRepresentation();
        user.setEnabled(enabled);
        userResource.update(user);
    }

    public void deleteUser(String keycloakUserId) {
        try (Response response = keycloak.realm(realm).users().delete(keycloakUserId)) {
            int status = response.getStatus();
            if (status != 204 && status != 200) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "";
                throw new ClientException("Keycloak delete user failed: HTTP " + status + " " + body, 409);
            }
        }
    }
}
