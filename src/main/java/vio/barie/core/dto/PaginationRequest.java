package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaginationRequest {
    @Builder.Default
    private int page = 0;
    
    @Builder.Default
    private int size = 20;
    
    @Builder.Default
    private String sortBy = "id";
    
    @Builder.Default
    private String sortDirection = "DESC";

    private String province;

    private String branchName;
}

