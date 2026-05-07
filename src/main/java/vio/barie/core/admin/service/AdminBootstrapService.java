package vio.barie.core.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.admin.dto.CreateOrgRequest;
import vio.barie.core.admin.dto.CreateUserRequest;
import vio.barie.core.admin.dto.CreateUserResponse;
import vio.barie.core.common.CommonStatus;
import vio.barie.core.common.RoleEnum;
import vio.barie.core.common.UserStatus;
import vio.barie.core.config.exception.ClientException;
import vio.barie.core.dto.organize.OrganizeResponse;
import vio.barie.core.entity.BranchEntity;
import vio.barie.core.entity.OrganizeEntity;
import vio.barie.core.entity.RoleEntity;
import vio.barie.core.entity.SysUserEntity;
import vio.barie.core.repository.BranchRepository;
import vio.barie.core.repository.OrganizeRepository;
import vio.barie.core.repository.RoleRepository;
import vio.barie.core.repository.SysUserRepository;
import vio.barie.core.service.KeycloakAdminService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    @Value("${bootstrap.admin.token}")
    private String bootstrapToken;

    private final OrganizeRepository organizeRepository;
    private final RoleRepository roleRepository;
    private final SysUserRepository userRepository;
    private final KeycloakAdminService keycloakAdminService;
    private final BranchRepository branchRepository;

    private static final String PASSWORD_HASH_KEYCLOAK_ONLY = "KEYCLOAK_MANAGED";

    private void checkToken(String token) {
        // bootstrapToken must be set via environment or application properties
        if (bootstrapToken == null || bootstrapToken.isBlank() || !Objects.equals(token, bootstrapToken)) {
            throw new ClientException("Invalid bootstrap token", 403);
        }
    }

    @Transactional
    public OrganizeResponse createOrganize(CreateOrgRequest req, String token) {
        checkToken(token);
        if (req.getName() == null || req.getName().isBlank()) throw new ClientException("name required", 400);
        OrganizeEntity org = new OrganizeEntity();
        org.setName(req.getName().trim());
        org.setOrgCode(req.getOrgCode() != null ? req.getOrgCode().trim() : "");
        org.setAddress(req.getAddress() != null ? req.getAddress().trim() : "");
        org.setStatus(CommonStatus.ACTIVE);
        org = organizeRepository.save(org);
        return OrganizeEntity.toResponse(org);
    }

    @Transactional
    public CreateUserResponse createRootUser(CreateUserRequest req, String token) {
        checkToken(token);

        if (req.getUsername() == null || req.getUsername().isBlank())
            throw new ClientException("username required", 400);
        if (req.getEmail() == null || req.getEmail().isBlank()) throw new ClientException("email required", 400);
        if (req.getPassword() == null || req.getPassword().isBlank())
            throw new ClientException("password required", 400);
        if (req.getOrganizeId() == null) throw new ClientException("organizeId required", 400);

        if (userRepository.existsByUsernameIgnoreCase(req.getUsername().trim())) {
            throw new ClientException("Username already used", 409);
        }
        if (userRepository.existsByEmailIgnoreCase(req.getEmail().trim())) {
            throw new ClientException("Email already used", 409);
        }

        OrganizeEntity org = organizeRepository.findById(req.getOrganizeId()).orElseThrow(() -> new ClientException("Organize not found", 404));

        RoleEntity role = roleRepository.findByRoleCode(RoleEnum.ROLE_ORG_ROOT.name()).orElseThrow(() -> new ClientException("Role ROLE_ORG_ROOT not found", 404));

        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("orgId", List.of(String.valueOf(org.getId())));
        attrs.put("role", List.of(RoleEnum.ROLE_ORG_ROOT.name()));

        String keycloakUserId = keycloakAdminService.createUser(req.getUsername().trim(), req.getEmail().trim(), req.getPassword(), attrs);

        SysUserEntity user = new SysUserEntity();
        try {
            user.setOrganize(org);
            if (req.getBranchId() != null) {
                BranchEntity branch = branchRepository.findByIdAndOrganize_Id(req.getBranchId(), org.getId())
                        .orElseThrow(() -> new ClientException("Branch not found in this organization", 404));
                user.setBranch(branch);
            }

            user.setUsername(req.getUsername().trim());
            user.setEmail(req.getEmail().trim());
            user.setKeycloakUserId(keycloakUserId);
            user.setPasswordHash(PASSWORD_HASH_KEYCLOAK_ONLY);
            user.setStatus(UserStatus.ACTIVE.name());
            user.setPhone(req.getPhone());
            user.setFullname(req.getFullname());
            user.getRoles().add(role);

            user = userRepository.save(user);
        } catch (RuntimeException e) {
            try {
                keycloakAdminService.deleteUser(keycloakUserId);
            } catch (Exception ex) {
                // log error but throw original exception
            }
            throw e;
        }

        return CreateUserResponse.builder().userId(user.getId()).keycloakUserId(keycloakUserId).organizeId(org.getId()).build();
    }

}


