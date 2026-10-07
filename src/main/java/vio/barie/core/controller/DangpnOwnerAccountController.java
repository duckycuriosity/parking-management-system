package vio.barie.core.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountPaginationRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.ParkingCustomerUpdateRequest;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionRegistrantPageRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionVehicleCreateRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.VehicleUserSaveRequest;
import vio.barie.core.service.OwnerAccountProfileService;
import vio.barie.core.service.OwnerAccountService;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/dangpnOwnerAccount")
@RequiredArgsConstructor
public class DangpnOwnerAccountController {

    private final OwnerAccountService ownerAccountService;
    private final OwnerAccountProfileService ownerAccountProfileService;

    // Lấy thông tin cơ bản của một chủ hồ sơ theo ownerId.
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return Result.success(ownerAccountService.getById(id));
    }

    // Lấy danh sách chủ hồ sơ có lọc và phân trang bằng body pagination request.
    @PostMapping("/list")
    public ResponseEntity<?> list(@Valid @RequestBody(required = false) OwnerAccountPaginationRequest paginationRequest) {
        if (paginationRequest == null) {
            paginationRequest = OwnerAccountPaginationRequest.builder().build();
        }
        Page<?> result = ownerAccountService.list(paginationRequest);
        return Result.success(result);
    }

    // lấy thông tin chủ hồ sơ
    @PostMapping("/subscription-registrants/list")
    public ResponseEntity<?> listSubscriptionRegistrants(@Valid @RequestBody(required = false) SubscriptionRegistrantPageRequest request) {
        if (request == null) {
            request = SubscriptionRegistrantPageRequest.builder().build();
        }
        return Result.success(ownerAccountService.listSubscriptionRegistrants(request));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody OwnerAccountRequest request) {
        return Result.success(ownerAccountService.create(request));
    }

    // Tạo trọn bộ hồ sơ gồm chủ hồ sơ, xe, người sử dụng xe và quan hệ xe-người dùng.
    @PostMapping("/profiles")
    public ResponseEntity<?> createProfile(@Valid @RequestBody ProfileSaveRequest request) {
        return Result.success(ownerAccountProfileService.createProfile(request));
    }

    @PostMapping(value = "/profiles/with-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<?> createProfileWithImages(
            @Valid @RequestPart("request") ProfileSaveRequest request,
            @Parameter(
                    description = "Customer face images. Map each file to customers by customer.imageIndex, or omit imageIndex to map files sequentially.",
                    content = @Content(array = @ArraySchema(schema = @Schema(type = "string", format = "binary")))
            )
            @RequestPart(value = "customerImages", required = false) MultipartFile[] customerImages,
            @Parameter(
                    description = "Owner account face image. This is saved to owner_account.image_url.",
                    content = @Content(schema = @Schema(type = "string", format = "binary"))
            )
            @RequestPart(value = "ownerImage", required = false) MultipartFile ownerImage) {
        List<MultipartFile> images = customerImages == null ? null : Arrays.asList(customerImages);
        return Result.success(ownerAccountProfileService.createProfile(request, images, ownerImage));
    }

    // Thêm xe mới vào một người đăng ký thuê bao và gắn người sử dụng xe ban đầu.
    @PostMapping("/subscription-registrants/{ownerId}/vehicles")
    public ResponseEntity<?> addSubscriptionRegistrantVehicle(
            @PathVariable Long ownerId,
            @Valid @RequestBody SubscriptionVehicleCreateRequest request) {
        return Result.success(ownerAccountProfileService.addVehicle(ownerId, request));
    }

    @GetMapping("/subscription-registrants/{ownerId}/vehicles")
    public ResponseEntity<?> listRegisteredVehicles(@PathVariable Long ownerId) {
        return Result.success(ownerAccountService.listRegisteredVehicles(ownerId));
    }

    // Cập nhật riêng thông tin hồ sơ người sử dụng xe theo customerId.
    @PatchMapping("/customers/{customerId}")
    public ResponseEntity<?> updateCustomer(
            @PathVariable Long customerId,
            @Valid @RequestBody ParkingCustomerUpdateRequest request) {
        return Result.success(ownerAccountProfileService.updateCustomer(customerId, request));
    }

    // Thêm mới hoặc cập nhật người sử dụng xe rồi upsert quan hệ người dùng với xe.
    @PostMapping("/vehicles/{vehicleId}/users")
    public ResponseEntity<?> saveVehicleUser(
            @PathVariable Long vehicleId,
            @Valid @RequestBody VehicleUserSaveRequest request) {
        return Result.success(ownerAccountProfileService.saveVehicleUser(vehicleId, request));
    }


    // Cập nhật thông tin chủ hồ sơ cơ bản theo ownerId.
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody OwnerAccountRequest request) {
        return Result.success(ownerAccountService.update(id, request));
    }

    // Xóa chủ hồ sơ theo ownerId.
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        ownerAccountService.delete(id);
        return Result.success(id);
    }
}
