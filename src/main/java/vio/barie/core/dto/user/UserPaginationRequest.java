package vio.barie.core.dto.user;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import vio.barie.core.dto.PaginationRequest;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
public class UserPaginationRequest extends PaginationRequest {
    private String username;
    private String email;
    private Long branchId;
    private String phone;
    private String searchTerm;
    private String status;
}
