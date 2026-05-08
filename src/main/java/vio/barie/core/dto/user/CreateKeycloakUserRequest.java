package vio.barie.core.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateKeycloakUserRequest {

    @NotBlank
    @Size(max = 255)
    private String username;


    @NotBlank
    @Size(max = 255)
    private String fullname;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    private String password;
    private String phone;
    private String status;

    @NotNull
    private List<String> roleCodes;

    private Long branchId;

}
