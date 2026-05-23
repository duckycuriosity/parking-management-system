package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vio.barie.core.common.UserStatus;
import vio.barie.core.dto.user.UserDTO;
import vio.barie.core.entity.PermissionEntity;
import vio.barie.core.entity.RoleEntity;
import vio.barie.core.entity.SysUserEntity;
import vio.barie.core.repository.SysUserRepository;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CurrentUserService {
    private final SysUserRepository userRepository;

    public UserDTO loadCurrentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("Invalid token");
        }

        String keycloakUserId = jwt.getSubject();

        return getCurrentUser(keycloakUserId);
    }

    public UserDTO getCurrentUser(String keycloakUserId) {
        SysUserEntity user = userRepository.findWithRolesAnPermissionsByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));

        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new AccessDeniedException("User is not active");
        }

        Set<String> roles = user.getRoles().stream()
                .map(RoleEntity::getRoleCode)
                .collect(Collectors.toSet());

        Set<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(PermissionEntity::getPermissionName)
                .collect(Collectors.toSet());

        Long branchId = user.getBranch() != null ? user.getBranch().getId() : null;

        return UserDTO.builder()
                .userId(user.getId())
                .keycloakUserId(user.getKeycloakUserId())
                .organizeCode(user.getOrganize().getOrgCode())
                .organizeId(user.getOrganize().getId())
                .branchId(branchId)
                .username(user.getUsername())
                .email(user.getEmail())
                .roles(roles)
                .permissions(permissions)
                .build();
    }
}
