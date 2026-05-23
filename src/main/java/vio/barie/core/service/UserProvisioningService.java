package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.RoleEnum;
import vio.barie.core.common.UserStatus;
import vio.barie.core.config.exception.ClientException;
import vio.barie.core.config.filter.UserContext;
import vio.barie.core.dto.branch.BranchResponse;
import vio.barie.core.dto.organize.OrganizeResponse;
import vio.barie.core.dto.role.RoleResponse;
import vio.barie.core.dto.user.*;
import vio.barie.core.entity.BranchEntity;
import vio.barie.core.entity.OrganizeEntity;
import vio.barie.core.entity.RoleEntity;
import vio.barie.core.entity.SysUserEntity;
import vio.barie.core.repository.*;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProvisioningService {


    private final KeycloakAdminService keycloakAdminService;
    private final SysUserRepository userRepository;
    private final OrganizeRepository organizeRepository;
    private final BranchRepository branchRepository;

    public static final String PASSWORD_HASH_KEYCLOAK_ONLY = "KEYCLOAK_MANAGED";

    private final RoleRepository roleRepository;

    // Map role nào được phép gán role nào
    private static final Map<RoleEnum, Set<RoleEnum>> ALLOWED_ASSIGN_ROLES = Map.of(
            RoleEnum.ROLE_ORG_ROOT, Set.of(RoleEnum.ROLE_ORG_EDIT, RoleEnum.ROLE_ORG_VIEW, RoleEnum.ROLE_BRANCH_EDIT, RoleEnum.ROLE_BRANCH_VIEW),
            RoleEnum.ROLE_ORG_EDIT, Set.of(RoleEnum.ROLE_ORG_VIEW, RoleEnum.ROLE_BRANCH_EDIT, RoleEnum.ROLE_BRANCH_VIEW),
            RoleEnum.ROLE_BRANCH_EDIT, Set.of(RoleEnum.ROLE_BRANCH_VIEW));

    @Transactional
    public CreateKeycloakUserResponse provision(CreateKeycloakUserRequest request) {
        if (StringUtils.isBlank(request.getPhone())) request.setPhone(null);
        UserDTO userContext = UserContext.get();
        log.info("provision: requested by userId={} orgId={} username={}", userContext.getUserId(), userContext.getOrganizeId(), userContext.getUsername());
        // 1. Lấy role của người đang thực hiện
        RoleEnum callerRole = resolveCallerRole(userContext);
        log.debug("provision: callerRole={}", callerRole);

        // 2. Kiểm tra có được phép gán role này không
        List<RoleEntity> roleEntityList = roleRepository.findByRoleCodeIn(request.getRoleCodes());
        if (request.getRoleCodes().size() != roleEntityList.size()) {
            log.warn("provision: some roles not found requested={} found={}", request.getRoleCodes(), roleEntityList.stream().map(RoleEntity::getRoleCode).toList());
            throw new ClientException("One or more roles not found", 404);
        }
        for (RoleEntity role : roleEntityList) {
            validateRoleAssignment(callerRole, RoleEnum.valueOf(role.getRoleCode()));
        }

        // 3. Check duplicate
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new ClientException("Username đã được sử dụng", 409);
        }
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new ClientException("Email đã được sử dụng", 409);
        }
        if (StringUtils.isNotBlank(request.getPhone()) && userRepository.existsByPhoneIgnoreCase(request.getPhone())) {
            throw new ClientException("Số điện thoại đã được sử dụng", 409);
        }

        // 4. Lấy org từ DB (không set ID thô)
        Long orgId = userContext.getOrganizeId();
        OrganizeEntity organize = organizeRepository.findById(orgId).orElseThrow(() -> new ClientException("Organize not found", 404));

        // 5. Tạo user trên Keycloak
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("orgId", List.of(String.valueOf(orgId)));

        String keycloakUserId;
        try {
            log.info("provision: creating keycloak user username={} email={} orgId={} roles={}", request.getUsername(), request.getEmail(), orgId, request.getRoleCodes());
            keycloakUserId = keycloakAdminService.createUser(request.getUsername().trim(), request.getEmail().trim(), request.getPassword(), attrs);
            log.info("provision: keycloak created id={}", keycloakUserId);
        } catch (Exception e) {
            throw new ClientException("Keycloak error: " + e.getMessage(), 409);
        }

        // 6. Lưu user vào DB
        SysUserEntity user = new SysUserEntity();
        try {
            if (request.getBranchId() == null) {
                if (!userContext.hasAnyRole(RoleEnum.ONLY_ORG_EDIT()))
                    throw new ClientException("Branch ID is required not null", 400);
                else {
                    for (String roleCode : request.getRoleCodes()) {
                        if (Arrays.stream(RoleEnum.REQUIRED_BRANCH()).toList().contains(roleCode)) {
                            throw new ClientException("Xin chọn chi nhánh", 400);
                        }
                    }
                }
            } else {
                BranchEntity branchEntity = branchRepository.findByIdAndOrganize_Id(request.getBranchId(), orgId).orElseThrow(() -> new ClientException("Branch not found orgId " + orgId, 404));

                if (userContext.hasAnyRole(RoleEnum.ONLY_ORG_EDIT())) {
                    user.setBranch(branchEntity);

                } else if (userContext.hasAnyRole(RoleEnum.ROLE_BRANCH_EDIT.authority())) {
                    if (!branchEntity.getId().equals(userContext.getBranchId()))
                        throw new ClientException("You can only assign branch to your own branch", 403);
                    user.setBranch(branchEntity);
                } else {
                    throw new ClientException("You don't have permission to assign branch", 403);
                }

            }


            user.setOrganize(organize);
            user.getRoles().addAll(roleEntityList);
            user.setUsername(request.getUsername().trim());
            user.setEmail(request.getEmail().trim());
            user.setPasswordHash(PASSWORD_HASH_KEYCLOAK_ONLY);
            user.setKeycloakUserId(keycloakUserId);
            user.setStatus(request.getStatus());
            user.setPhone(request.getPhone());
            user.setFullname(request.getFullname());

            user = userRepository.save(user);
            log.info("provision: saved user dbId={} keycloakId={} username={}", user.getId(), keycloakUserId, user.getUsername());
        } catch (RuntimeException e) {
            // Rollback Keycloak nếu DB lỗi
            log.error("provision: db save failed, attempt rollback keycloakId={}: {}", keycloakUserId, e.getMessage());
            try {
                keycloakAdminService.deleteUser(keycloakUserId);
            } catch (Exception ex) {
                log.error("Failed to rollback Keycloak user {}: {}", keycloakUserId, ex.getMessage());
            }
            throw e;
        }

        return CreateKeycloakUserResponse.builder().
                userId(user.getId()).
                keycloakUserId(keycloakUserId).
                organizeId(orgId).
                build();
    }

    private RoleEnum resolveCallerRole(UserDTO userContext) {
        // Ưu tiên theo thứ tự từ cao xuống thấp
        List<RoleEnum> priority = List.of(RoleEnum.ROLE_ORG_ROOT, RoleEnum.ROLE_ORG_EDIT, RoleEnum.ROLE_BRANCH_EDIT);

        Set<String> callerRoles = userContext.getRoles(); // giả sử UserDTO có getRoles()
        log.debug("resolveCallerRole: userId={} roles={}", userContext.getUserId(), callerRoles);
        RoleEnum resolved = priority.stream().filter(r -> callerRoles.contains(r.name())).findFirst().orElseThrow(() -> new ClientException("Insufficient permissions", 403));
        log.debug("resolveCallerRole: resolved={}", resolved);
        return resolved;
    }

    private void validateRoleAssignment(RoleEnum callerRole, RoleEnum targetRole) {
        Set<RoleEnum> allowed = ALLOWED_ASSIGN_ROLES.getOrDefault(callerRole, Set.of());
        log.debug("validateRoleAssignment: callerRole={} targetRole={} allowed={}", callerRole, targetRole, allowed);
        if (!allowed.contains(targetRole)) {
            log.warn("validateRoleAssignment: not allowed callerRole={} targetRole={}", callerRole, targetRole);
            throw new ClientException("Bạn không đủ quyền thao tác", 403);
        }
    }


    @Transactional
    public UpdateUserResponse update(Long userId, UpdateUserRequest request) {
        if (StringUtils.isBlank(request.getPhone())) request.setPhone(null);
        UserDTO userContext = UserContext.get();
        Long orgId = userContext.getOrganizeId();
        log.info("update: requested by userId={} targetUserId={} orgId={}", userContext.getUserId(), userId, orgId);

        SysUserEntity user = userRepository.findByIdAndOrganize_Id(userId, orgId)
                .orElseThrow(() -> new ClientException("User not found", 404));

        if (user.getId().equals(userContext.getUserId())) {
            throw new ClientException("Vui lòng cập nhật trong profile", 403);
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            boolean phoneExists = userRepository.existsByPhoneAndIdNot(
                    request.getPhone(), userId);
            if (phoneExists) {
                throw new ClientException("Số điện thoại đã được sử dụng", 409);
            }
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            boolean emailExist = userRepository.existsByEmailIgnoreCaseAndIdNot(
                    request.getEmail(), userId);
            if (emailExist) {
                throw new ClientException("Email đã được sử dụng", 409);
            }
        }


        RoleEnum callerRole = resolveCallerRole(userContext);
        log.debug("update: callerRole={}", callerRole);

        List<RoleEntity> roleEntityList = roleRepository.findByRoleCodeIn(request.getRoleCodes());
        if (request.getRoleCodes().size() != roleEntityList.size()) {
            log.warn("update: some roles not found requested={} found={}", request.getRoleCodes(), roleEntityList.stream().map(RoleEntity::getRoleCode).toList());
            throw new ClientException("One or more roles not found", 404);
        }
        for (RoleEntity role : roleEntityList) {
            validateRoleAssignment(callerRole, RoleEnum.valueOf(role.getRoleCode()));
        }

        BranchEntity branchEntity = null;
        if (request.getBranchId() != null) {
            branchEntity = branchRepository.findByIdAndOrganize_Id(request.getBranchId(), orgId)
                    .orElseThrow(() -> new ClientException("Branch not found", 404));
        }

        user.setEmail(request.getEmail());
        user.setStatus(request.getStatus());
        user.setPhone(request.getPhone());
        user.setFullname(request.getFullname());
        user.getRoles().clear();
        user.getRoles().addAll(roleEntityList);

        for (String role : userContext.getRoles()) {
            if (Arrays.stream(RoleEnum.REQUIRED_BRANCH()).toList().contains(role)) {
                if (branchEntity == null) {
                    throw new ClientException("Xin chọn chi nhánh", 400);
                }
            }
        }

        if (Arrays.stream(RoleEnum.ONLY_ORG_EDIT()).anyMatch(r -> r.equals(callerRole.name()))) {
            user.setBranch(branchEntity);
            user = userRepository.save(user);
            log.info("update: user saved id={} roles={} status={}", user.getId(), user.getRoles().stream().map(RoleEntity::getRoleCode).toList(), user.getStatus());

            if (!Objects.equals(user.getStatus(), request.getStatus())) {
                boolean enabled = UserStatus.ACTIVE.name().equals(request.getStatus());
                keycloakAdminService.setUserEnabled(user.getKeycloakUserId(), enabled);
            }


            return UpdateUserResponse.builder()
                    .userId(user.getId())
                    .roles(user.getRoles().stream().map(RoleEntity::getRoleCode).toList())
                    .status(user.getStatus())
                    .build();
        } else if (RoleEnum.ROLE_BRANCH_EDIT.equals(callerRole)) {
            if (branchEntity == null || !branchEntity.getId().equals(userContext.getBranchId())) {
                throw new ClientException("You can only assign users to your own branch", 403);
            }

            user = userRepository.save(user);
            log.info("update: user saved id={} roles={} status={}", user.getId(), user.getRoles().stream().map(RoleEntity::getRoleCode).toList(), user.getStatus());

            boolean enabled = UserStatus.ACTIVE.name().equals(request.getStatus());
            keycloakAdminService.setUserEnabled(user.getKeycloakUserId(), enabled);

            return UpdateUserResponse.builder()
                    .userId(user.getId())
                    .roles(user.getRoles().stream().map(RoleEntity::getRoleCode).toList())
                    .status(user.getStatus())
                    .build();
        }

        throw new ClientException("You don't have permission to update user", 403);
    }


    @Transactional(readOnly = true)
    public Page<UserResponseDTO> list(UserPaginationRequest request) {
        UserDTO userContext = UserContext.get();
        Long orgId = userContext.getOrganizeId();
        Long branchId = userContext.getBranchId();
        log.debug("list: requested by userId={} orgId={} branchId={} request={}", userContext.getUserId(), orgId, branchId, request);

        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), Sort.by("createdAt").descending());

        // Nếu user là BRANCH_VIEW hoặc BRANCH_EDIT thì chỉ xem user trong branch đó
        Long filterBranchId = null;
        if (userContext.hasAnyRole(RoleEnum.ROLE_BRANCH_VIEW.authority(), RoleEnum.ROLE_BRANCH_EDIT.authority())) {
            filterBranchId = branchId;
        } else if (request.getBranchId() != null) {
            // Nếu là ORG_ROOT/ORG_EDIT có thể filter theo branchId từ request
            filterBranchId = request.getBranchId();
        }

        return userRepository.findAll(
                SysUserSpecification.filterByOrganizeIdAndFilters(orgId, request.getUsername(), request.getEmail(), request.getPhone(), filterBranchId, request.getSearchTerm(), request.getStatus()),
                pageable
        ).map(this::toUserResponse);
    }

    @Transactional(readOnly = true)
    public UserResponseDTO getProfile() {
        UserDTO userContext = UserContext.get();
        Long orgId = userContext.getOrganizeId();
        log.debug("getProfile: userId={} orgId={}", userContext.getUserId(), orgId);

        SysUserEntity user = userRepository.findByIdAndOrganize_Id(userContext.getUserId(), orgId)
                .orElseThrow(() -> new ClientException("User not found", 404));

        return toUserResponse(user);
    }

    @Transactional
    public UserResponseDTO updateProfile(UpdateProfileRequest request) {
        UserDTO userContext = UserContext.get();
        Long orgId = userContext.getOrganizeId();
        log.info("updateProfile: userId={} orgId={} fullname={}", userContext.getUserId(), orgId, request.getFullname());

        SysUserEntity user = userRepository.findByIdAndOrganize_Id(userContext.getUserId(), orgId)
                .orElseThrow(() -> new ClientException("User not found", 404));

        user.setPhone(request.getPhone().trim());
        user.setFullname(request.getFullname());

        user = userRepository.save(user);
        log.debug("updateProfile: saved userId={} fullname={}", user.getId(), user.getFullname());

        return toUserResponse(user);
    }


    private UserResponseDTO toUserResponse(SysUserEntity user) {
        OrganizeResponse organize = OrganizeEntity.toResponse(user.getOrganize());
        BranchResponse branch = BranchEntity.toResponse(user.getBranch());
        Set<RoleEntity> roles = user.getRoles();
        Set<RoleResponse> roleResponses = new HashSet<>();

        for (RoleEntity roleEntity : roles) {
            roleResponses.add(RoleEntity.toResponse(roleEntity));
        }
        return UserResponseDTO.builder()
                .phone(user.getPhone())
                .id(user.getId())
                .fullname(user.getFullname())
                .username(user.getUsername())
                .email(user.getEmail())
                .organizeId(user.getOrganize().getId())
                .organizeName(user.getOrganize().getName())
                .branchId(user.getBranch() != null ? user.getBranch().getId() : null)
                .branchName(user.getBranch() != null ? user.getBranch().getBranchName() : null)
                .roleCodes(roles.stream().map(RoleEntity::getRoleCode).toList())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .branch(branch)
                .org(organize)
                .roles(roleResponses)
                .build();
    }

    @Transactional
    public void delete(Long userId) {
        UserDTO userContext = UserContext.get();
        Long orgId = userContext.getOrganizeId();
        log.info("delete: requested by userId={} targetUserId={} orgId={}", userContext.getUserId(), userId, orgId);

        SysUserEntity user = userRepository.findByIdAndOrganize_Id(userId, orgId)
                .orElseThrow(() -> new ClientException("User not found", 404));

        if (user.getId().equals(userContext.getUserId())) {
            throw new ClientException("You cannot delete your own account", 403);
        }

        RoleEnum callerRole = resolveCallerRole(userContext);

        // 2. Kiểm tra có được phép xóa role này không
        Set<RoleEntity> roleEntityList = user.getRoles();

        for (RoleEntity role : roleEntityList) {
            validateRoleAssignment(callerRole, RoleEnum.valueOf(role.getRoleCode()));
        }


        if (RoleEnum.ROLE_BRANCH_EDIT.equals(callerRole)) {
            if (user.getBranch() == null || !user.getBranch().getId().equals(userContext.getBranchId())) {
                throw new ClientException("You can only delete users in your own branch", 403);
            }
        }

        userRepository.delete(user);
        log.debug("delete: deleted user from DB id={}", user.getId());

        try {
            keycloakAdminService.deleteUser(user.getKeycloakUserId());
            log.info("delete: deleted user in Keycloak keycloakId={}", user.getKeycloakUserId());
        } catch (RuntimeException e) {
            // Log and rethrow as ClientException to trigger transaction rollback
            log.error("Failed to delete user in Keycloak {}: {}", user.getKeycloakUserId(), e.getMessage());
            throw new ClientException("Failed to delete user in Keycloak: " + e.getMessage(), 409);
        }
    }

}
