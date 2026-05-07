package vio.barie.core.dto.organize;

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
public class OrganizeResponse {
    private Long id;
    private String name;
    private String orgCode;
    private String address;
    private CommonStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

