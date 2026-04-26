package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.user.*;
import vio.barie.core.service.UserProvisioningService;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProvisioningController {

    private final UserProvisioningService userProvisioningService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> me() {
        UserResponseDTO dto = userProvisioningService.getProfile();
        return Result.success(dto);
    }

    @PutMapping("/updateProfile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        UserResponseDTO dto = userProvisioningService.updateProfile(request);
        return Result.success(dto);
    }

    @DeleteMapping
    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> deleteUser( @RequestBody List<Long> ids) {
        if (ids.size() > 15) throw new ServiceException("Cannot delete more record at once", 400);
        for (Long id : ids) {
            userProvisioningService.delete(id);
        }
        return Result.success(ids);
    }

    @PostMapping("/create")
    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> createWithKeycloak(@Valid @RequestBody CreateKeycloakUserRequest request) {
        return Result.success(userProvisioningService.provision(request));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<UpdateUserResponse> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userProvisioningService.update(userId, request));
    }

    @PostMapping("/list")
    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    public ResponseEntity<?> list(@Valid @RequestBody(required = false) UserPaginationRequest request) {
        if (request == null) {
            request = UserPaginationRequest.builder().build();
        }
        Page<?> result = userProvisioningService.list(request);
        return Result.success(result);
    }
}
