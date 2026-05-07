package vio.barie.core.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @Size(max = 50)
    private String phone;
    private String fullname;

}

