package vio.barie.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vio.barie.core.common.CommonStatus;
import vio.barie.core.dto.branch.BranchResponse;
import vio.barie.core.entity.base.BaseEntity;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "branch",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_branch_organize_code", columnNames = {"organize_id", "branch_code"})
        }
)
public class BranchEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organize_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private OrganizeEntity organize;

    @Column(name = "branch_code", nullable = false, length = 255, unique = true)
    private String branchCode;

    @Column(nullable = false, length = 255)
    private String address;
    private String province;
    private String managerName;
    private String branchName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommonStatus status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    //    @OneToMany(mappedBy = "branch")
//    private List<UserEntity> users = new ArrayList<>();
    private Double latitude;
    private Double longitude;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
    public static BranchResponse toResponse(BranchEntity b) {
        if (b == null) return null;
        OrganizeEntity org = b.getOrganize();
        return BranchResponse.builder().latitude(b.getLatitude()).longitude(b.getLongitude()).id(b.getId()).branchName(b.getBranchName()).managerName(b.getManagerName()).province(b.getProvince()).description(b.getDescription()).organizeId(org.getId()).organizeName(org.getName()).branchCode(b.getBranchCode()).address(b.getAddress()).status(b.getStatus()).createdAt(b.getCreatedAt()).updatedAt(b.getUpdatedAt()).build();
    }

}
