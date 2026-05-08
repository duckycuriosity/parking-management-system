package vio.barie.core.dto.branch;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BranchUpdateRequest {
    @NotBlank
    private String branchCode;

    @NotBlank
    private String address;
    private String managerName;
    @NotBlank
    private String status;
    private String description;
    private String province;
    private String branchName;
    private Double latitude;
    private Double longitude;
}
