package vio.barie.core.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateUserRequest {

    @Email
    @Size(max = 255)
    private String email;

    private String phone;
    private String fullname;

    private List<String> roleCodes;

    private Long branchId;
    @NotBlank
    private String status;
}
