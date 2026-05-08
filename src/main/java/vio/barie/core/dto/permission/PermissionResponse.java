package vio.barie.core.dto.permission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PermissionResponse {
    private Long id;
    private String permissionName;
    private String resource;
    private String description;
    private LocalDateTime createdAt;
}

