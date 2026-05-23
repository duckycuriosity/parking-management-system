package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vio.barie.core.common.CommonStatus;
import vio.barie.core.dto.organize.OrganizeResponse;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "organize")
public class OrganizeEntity extends BaseEntity {
    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "org_code", nullable = false, length = 255)
    private String orgCode;

    @Column(nullable = false, length = 255)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommonStatus status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

//    @OneToMany(mappedBy = "organize", cascade = CascadeType.ALL, orphanRemoval = false)
//    private List<BranchEntity> branches = new ArrayList<>();
//
//    @OneToMany(mappedBy = "organize", cascade = CascadeType.ALL, orphanRemoval = false)
//    private List<UserEntity> users = new ArrayList<>();
//
//    @OneToMany(mappedBy = "organize", cascade = CascadeType.ALL, orphanRemoval = false)
//    private List<RoleEntity> roles = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public static OrganizeResponse toResponse(OrganizeEntity organize){
        return OrganizeResponse.builder().id(organize.getId()).name(organize.getName()).orgCode(organize.getOrgCode()).address(organize.getAddress()).status(organize.getStatus()).createdAt(organize.getCreatedAt()).updatedAt(organize.getUpdatedAt()).build();
    }

}
