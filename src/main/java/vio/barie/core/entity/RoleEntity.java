package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vio.barie.core.dto.role.RoleResponse;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(
        name = "role",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_role_organize_name", columnNames = {"role_name"})
        }
)
public class RoleEntity extends BaseEntity {
//    @ManyToOne(fetch = FetchType.LAZY, optional = false)
//    @JoinColumn(name = "organize_id", nullable = false)
//    private OrganizeEntity organize;

    @Column(name = "role_code", nullable = false, length = 255)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 255)
    private String rolename;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToMany(mappedBy = "roles")
    private Set<SysUserEntity> users = new HashSet<>();

    @ManyToMany
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<PermissionEntity> permissions = new HashSet<>();

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public static RoleResponse toResponse(RoleEntity role) {
        if (role == null) return null;

        return RoleResponse.builder()
                .id(role.getId())
                .roleCode(role.getRoleCode())
                .rolename(role.getRolename())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();

    }
}
