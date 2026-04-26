package vio.barie.core.dto.user;

import lombok.Builder;
import lombok.Data;
import vio.barie.core.dto.branch.BranchResponse;
import vio.barie.core.dto.organize.OrganizeResponse;
import vio.barie.core.dto.role.RoleResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
public class UserResponseDTO {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private String status;
    private String organizeName;
    private String fullname;
    private String keycloakUserId;
    private Long organizeId;
    private Long branchId;
    private String branchName;
    private List<String> roleCodes = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private OrganizeResponse org;
    private BranchResponse branch;
    private Set<RoleResponse> roles = new HashSet<>();
}
