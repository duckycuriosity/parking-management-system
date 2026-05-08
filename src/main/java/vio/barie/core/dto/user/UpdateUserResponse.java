package vio.barie.core.dto.user;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UpdateUserResponse {
    private Long userId;
    private List<String> roles;
    private String status;
}