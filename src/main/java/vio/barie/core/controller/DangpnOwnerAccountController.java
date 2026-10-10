package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.*;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.service.OwnerAccountAiIdentityService;
import vio.barie.core.service.OwnerAccountProfileService;
import vio.barie.core.service.OwnerAccountService;
import vio.barie.core.service.VehicleSubscriptionPaymentService;
import java.util.Arrays;
import java.util.List;

/** The legacy base path remains an alias; commands have explicit owner scope. */
@RestController
@RequestMapping({"/api/owner-accounts", "/api/dangpnOwnerAccount"})
@RequiredArgsConstructor
@PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
public class DangpnOwnerAccountController {
    private final OwnerAccountService owners;
    private final OwnerAccountProfileService profiles;
    private final OwnerAccountAiIdentityService identities;
    private final VehicleSubscriptionPaymentService payments;

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) { return Result.success(owners.getById(id)); }

    @PostMapping("/list")
    public ResponseEntity<?> list(@Valid @RequestBody(required = false) OwnerAccountPaginationRequest request) {
        return Result.success(owners.list(request == null ? OwnerAccountPaginationRequest.builder().build() : request));
    }

    @PostMapping("/profiles")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> createProfile(@Valid @RequestBody ProfileSaveRequest request) {
        return Result.success(profiles.createProfile(request));
    }

    /** Compatibility transport only. No images are silently dropped or sent inside the DB command. */
    @Deprecated
    @PostMapping(value = "/profiles/with-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> createProfileMultipart(@Valid @RequestPart("request") ProfileSaveRequest request,
            @RequestPart(value = "customerImages", required = false) MultipartFile[] customerImages,
            @RequestPart(value = "ownerImage", required = false) MultipartFile[] ownerImages) {
        requireSeparateImages(customerImages, ownerImages);
        return Result.success(profiles.createProfile(request));
    }

    @PostMapping(value = {"/{ownerId}/vehicles", "/subscription-registrants/{ownerId}/vehicles"},
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> addVehicle(@PathVariable Long ownerId, @Valid @RequestBody SubscriptionVehicleCreateRequest request) {
        return Result.success(profiles.addVehicle(ownerId, request));
    }

    @Deprecated
    @PostMapping(value = {"/{ownerId}/vehicles", "/subscription-registrants/{ownerId}/vehicles"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> addVehicleMultipart(@PathVariable Long ownerId,
            @Valid @RequestPart("request") SubscriptionVehicleCreateRequest request,
            @RequestPart(value = "userImages", required = false) MultipartFile[] images) {
        requireSeparateImages(images);
        return Result.success(profiles.addVehicle(ownerId, request));
    }

    @GetMapping({"/{ownerId}/vehicles", "/subscription-registrants/{ownerId}/vehicles"})
    public ResponseEntity<?> listRegisteredVehicles(@PathVariable Long ownerId) {
        return Result.success(owners.listRegisteredVehicles(ownerId));
    }

    @PostMapping("/{ownerId}/vehicles/{vehicleId}/users")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> grantVehicleUser(@PathVariable Long ownerId, @PathVariable Long vehicleId,
            @Valid @RequestBody VehicleUserSaveRequest request) {
        return Result.success(profiles.saveVehicleUser(ownerId, vehicleId, request));
    }

    @DeleteMapping("/{ownerId}/vehicles/{vehicleId}/users/{customerId}")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> revokeVehicleUser(@PathVariable Long ownerId, @PathVariable Long vehicleId,
            @PathVariable Long customerId) {
        profiles.revokeVehicleUser(ownerId, vehicleId, customerId);
        return Result.success(customerId);
    }

    @PatchMapping("/{ownerId}/customers/{customerId}")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> updateCustomer(@PathVariable Long ownerId, @PathVariable Long customerId,
            @Valid @RequestBody ParkingCustomerUpdateRequest request) {
        return Result.success(profiles.updateCustomer(ownerId, customerId, request));
    }

    @PostMapping(value = "/{ownerId}/face", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> syncOwnerFace(@PathVariable Long ownerId,
            @RequestPart("images") List<MultipartFile> images) {
        return Result.success(identities.syncOwner(ownerId, images));
    }

    @PostMapping(value = "/{ownerId}/customers/{customerId}/face", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> syncDriverFace(@PathVariable Long ownerId, @PathVariable Long customerId,
            @RequestPart("images") List<MultipartFile> images) {
        return Result.success(identities.syncDriver(ownerId, customerId, images));
    }

    /** Its DB validation ends before payment creates a separate committed intent. */
    @PostMapping("/{ownerId}/subscriptions/{subscriptionId}/payment-link")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> createPaymentLink(@PathVariable Long ownerId, @PathVariable Long subscriptionId) {
        profiles.requireOwnedSubscription(ownerId, subscriptionId);
        return Result.success(payments.createPaymentLink(subscriptionId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody OwnerAccountRequest request) {
        return Result.success(owners.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("!@vehicleSubscriptionSecurity.authEnabled or hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        owners.delete(id);
        return Result.success(id);
    }

    private static void requireSeparateImages(MultipartFile[]... groups) {
        if (Arrays.stream(groups).anyMatch(group -> group != null && group.length > 0)) {
            throw new BusinessException("Save the profile first; upload images using the owner/customer face endpoints",
                    "PROFILE_IMAGES_SEPARATE_ACTION", 400);
        }
    }
}
