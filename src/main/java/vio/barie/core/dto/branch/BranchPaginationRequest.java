package vio.barie.core.dto.branch;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vio.barie.core.dto.PaginationRequest;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BranchPaginationRequest extends PaginationRequest {
    private String branchName;
    private String searchTerm;
    private String status;
    private String province;
}

