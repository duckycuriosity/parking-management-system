package vio.barie.core.dto.branch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vio.barie.core.common.CommonStatus;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BranchResponse {
    private Long id;
    private Long organizeId;
    private String organizeName;
    private String branchCode;
    private String branchName;
    private String address;
    private String description;
    private String province;
    private String managerName;
    private CommonStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Double latitude;
    private Double longitude;
}
