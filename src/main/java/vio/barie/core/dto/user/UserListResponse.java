package vio.barie.core.dto.user;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class UserListResponse {
    private Long id;

    private String username;

    private String email;

    private Long organizeId;

    private String organizeName;

    private Long branchId;

    private String branchName;

    private String status;

    private String phone;

    private List<String> roleCodes = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

