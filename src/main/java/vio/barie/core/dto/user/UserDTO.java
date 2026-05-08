package vio.barie.core.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import vio.barie.core.common.RoleEnum;

import java.util.Arrays;
import java.util.Set;

@Value
@Builder
@AllArgsConstructor
public class UserDTO {
    Long userId;
    String keycloakUserId;
    Long organizeId;
    String organizeCode;
    Long branchId;
    String username;
    String email;
    Set<String> roles;
    Set<String> permissions;

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(String... roles) {
        return Arrays.stream(roles).anyMatch(this.roles::contains);
    }

    public boolean hasOnlyRoleBranchEdit() {
        return hasRole(RoleEnum.ROLE_BRANCH_EDIT.authority()) && !hasAnyRole(RoleEnum.ONLY_ORG_EDIT());
    }

    public boolean hasOnlyRoleBranchView() {
        return hasRole(RoleEnum.ROLE_BRANCH_VIEW.authority()) && !hasAnyRole(RoleEnum.ONLY_ORG_EDIT());
    }


}