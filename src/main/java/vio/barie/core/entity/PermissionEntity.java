package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "permission")
public class PermissionEntity extends BaseEntity {
    @Column(name = "permission_name", nullable = false, unique = true, length = 255)
    private String permissionName;

    @Column(nullable = false, length = 255)
    private String resource;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToMany(mappedBy = "permissions")
    private Set<RoleEntity> roles = new HashSet<>();

    @PrePersist
    public void prePersist() {
        this.setCreatedAt(LocalDateTime.now());
    }
}
