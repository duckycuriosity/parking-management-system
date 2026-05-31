package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.ProfileService;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profileService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AiProfileResponse> create(
            @RequestParam("name") String name,
            @RequestParam(value = "groupId", defaultValue = "0") Integer groupId,
            @RequestParam("image") MultipartFile image) throws IOException {

        return Result.ok("Profile created successfully", profileService.create(name, groupId, image, ""));
    }

    @GetMapping
    public Result<List<AiProfileResponse>> findAll() {
        return Result.ok("Profile list fetched successfully", profileService.findAll());
    }


    @GetMapping("/{id}")
    public Result<AiProfileResponse> findById(@PathVariable Integer id) {
        return Result.ok("Profile fetched successfully",
                profileService.findById(id));
    }


    @PutMapping("/{id}")
    public Result<AiProfileResponse> update(
            @PathVariable Integer id,
            @RequestParam("name") String name,
            @RequestParam(value = "groupId", defaultValue = "0") Integer groupId,
            @RequestParam(value = "image", required = false) MultipartFile image)
            throws IOException {

        return Result.ok("Profile updated successfully",
                profileService.update(id, name, groupId, image));
    }


    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        profileService.delete(id);
        return Result.ok("Profile deleted successfully",
                null);
    }
}
