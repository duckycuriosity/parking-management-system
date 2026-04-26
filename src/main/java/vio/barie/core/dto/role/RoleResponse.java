package vio.barie.core.dto.role;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RoleResponse {
    private Long id;
    private String roleCode;
    private String rolename;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

