package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.ApiResponse;
import vio.barie.core.dto.ProfileCreateRequest;
import vio.barie.core.service.ProfileService;

import java.util.List;

@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profileService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AiProfileResponse> create(@Valid @RequestBody ProfileCreateRequest request) {
        return new ApiResponse<>(
                "SUCCESS",
                "Profile created successfully",
                profileService.create(request));
    }

    @GetMapping
    public ApiResponse<List<AiProfileResponse>> findAll() {
        return new ApiResponse<>(
                "SUCCESS",
                "Profile list fetched successfully",
                profileService.findAll());
    }

    // Lấy thông tin theo ID (GET)
    @GetMapping("/{id}")
    public ApiResponse<AiProfileResponse> findById(@PathVariable Integer id) {
        return new ApiResponse<>(
                "SUCCESS",
                "Profile fetched successfully",
                profileService.findById(id));
    }

    // Cập nhật Profile (PUT)
    @PutMapping("/{id}")
    public ApiResponse<AiProfileResponse> update(
            @PathVariable Integer id,
            @Valid @RequestBody ProfileCreateRequest request) {
        return new ApiResponse<>(
                "SUCCESS",
                "Profile updated successfully",
                profileService.update(id, request));
    }

    // Xóa Profile (DELETE)
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Integer id) {
        profileService.delete(id);
        return new ApiResponse<>(
                "SUCCESS",
                "Profile deleted successfully",
                null);
    }
}
