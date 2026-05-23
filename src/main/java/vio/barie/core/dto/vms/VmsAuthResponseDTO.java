package vio.barie.core.dto.vms;

import lombok.Data;

@Data
public class VmsAuthResponseDTO {
    private String token;
    private Integer expires_in;
}
