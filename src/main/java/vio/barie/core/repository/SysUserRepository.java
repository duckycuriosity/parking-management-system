package vio.barie.core.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.SysUserEntity;

import java.util.List;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUserEntity, Long> , JpaSpecificationExecutor<SysUserEntity> {
    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhoneAndIdNot(String phone, Long id);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    Optional<SysUserEntity> findByKeycloakUserId(String keycloakUserId);

    @EntityGraph(attributePaths = {
            "organize",
            "branch",
            "roles",
            "roles.permissions"
    })
    Optional<SysUserEntity> findWithRolesAnPermissionsByKeycloakUserId(String keycloakUserId);

    boolean existsByBranch_Id(Long branchId);

    Optional<SysUserEntity> findByIdAndOrganize_Id(Long userId, Long organizeId);

    List<SysUserEntity> findByBranch_IdAndOrganize_Id(Long branchId, Long organizeId);

    List<SysUserEntity> findAllByBranchId(Long branchId);

    @Query("SELECT u FROM SysUserEntity u JOIN u.roles r WHERE u.organize.id = :orgId AND r.roleCode IN :roles")
    List<SysUserEntity> findByOrganizeIdAndRolesIn(@Param("orgId") Integer orgId, @Param("roles") List<String> roles);


    @Query("SELECT u.username FROM SysUserEntity u WHERE u.id = :id")
    String findUsernameById(@Param("id") Long id);

    boolean existsByPhoneIgnoreCase(String phone);
}