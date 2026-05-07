package vio.barie.core.admin.controller;


import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.admin.dto.CreateOrgRequest;
import vio.barie.core.admin.dto.CreateUserRequest;
import vio.barie.core.admin.dto.CreateUserResponse;
import vio.barie.core.admin.service.AdminBootstrapService;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.organize.OrganizeResponse;

@RestController
@RequestMapping("/admin/bootstrap")
@RequiredArgsConstructor
public class AdminBootstrapController {

    private final AdminBootstrapService adminBootstrapService;

    @PostMapping("/org")
    public ResponseEntity<?> createOrg(@RequestHeader(value = "X-ADMIN-TOKEN", required = false) String token,
                                       @RequestBody CreateOrgRequest req) {
        OrganizeResponse resp = adminBootstrapService.createOrganize(req, token);
        return Result.success(resp);
    }

    @PostMapping("/root-user")
    public ResponseEntity<?> createRootUser(@RequestHeader(value = "X-ADMIN-TOKEN", required = false) String token,
                                            @RequestBody CreateUserRequest req) {
        CreateUserResponse resp = adminBootstrapService.createRootUser(req, token);
        return Result.success(resp);
    }

}
