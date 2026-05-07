package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.user.UserDTO;

@Service("authz")
@RequiredArgsConstructor
public class AuthorizationService {
    private final CurrentUserService currentUserService;

    public boolean hasPermission(Authentication authentication, String permission) {
        UserDTO user = currentUserService.loadCurrentUser(authentication);
        return user.getPermissions().contains(permission);
    }

    public boolean hasAnyPermission(Authentication authentication, String... permissions) {
        UserDTO user = currentUserService.loadCurrentUser(authentication);
        for (String permission : permissions) {
            if (user.getPermissions().contains(permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasRole(Authentication authentication, String role) {
        UserDTO user = currentUserService.loadCurrentUser(authentication);
        return user.getRoles().contains(role);
    }

    public boolean sameOrganize(Authentication authentication, Integer organizeId) {
        UserDTO user = currentUserService.loadCurrentUser(authentication);
        return user.getOrganizeId().equals(organizeId);
    }

    public boolean sameBranch(Authentication authentication, Integer branchId) {
        UserDTO user = currentUserService.loadCurrentUser(authentication);
        return user.getBranchId() != null && user.getBranchId().equals(branchId);
    }
}
