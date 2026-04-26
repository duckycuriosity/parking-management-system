package vio.barie.core.dto.branch;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BranchCreateRequest {

    @NotBlank
    private String branchCode;

    private String status;
    @NotBlank
    private String address;
    private String description;
    private String province;
    private String managerName;
    private String branchName;
    private Double latitude;
    private Double longitude;
}
