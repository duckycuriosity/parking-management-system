package vio.barie.core.admin.dto;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String username;
    private String email;
    private String password;
    private String fullname;
    private String phone;
    private Long organizeId;
    private Long branchId; // optional
}


