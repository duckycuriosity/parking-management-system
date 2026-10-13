package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.*;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.service.OwnerAccountProfileService;
import vio.barie.core.service.OwnerAccountService;

@RestController
@RequestMapping("/api/dangpnOwnerAccount")
@RequiredArgsConstructor
public class DangpnOwnerAccountController {
    private final OwnerAccountService ownerAccountService;
    private final OwnerAccountProfileService ownerAccountProfileService;

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return Result.success(ownerAccountService.getById(id));
    }

    @PostMapping("/list")
    public ResponseEntity<?> list(@Valid @RequestBody(required = false) OwnerAccountPaginationRequest request) {
        return Result.success(ownerAccountService.list(request == null ? OwnerAccountPaginationRequest.builder().build() : request));
    }


//    @PostMapping(value = "/profiles", consumes = MediaType.APPLICATION_JSON_VALUE)
//    public ResponseEntity<?> createProfile(@Valid @RequestBody ProfileSaveRequest request) {
//        return created(ownerAccountProfileService.createProfile(request));
//    }

    @PostMapping(value = "/profiles/with-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createProfileWithImages(@Valid @RequestBody ProfileSaveRequest request) {
        return created(ownerAccountProfileService.createProfile(request));
    }

    @PostMapping(value = "/subscription-registrants/{ownerId}/vehicles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addVehicle(@PathVariable Long ownerId,
            @Valid @RequestBody SubscriptionVehicleCreateRequest request) {
        return created(ownerAccountProfileService.addVehicle(ownerId, request));
    }

    @GetMapping("/subscription-registrants/{ownerId}/vehicles")
    public ResponseEntity<?> listRegisteredVehicles(@PathVariable Long ownerId) {
        return Result.success(ownerAccountService.listRegisteredVehicles(ownerId));
    }

//    @PatchMapping("/subscription-registrants/{ownerId}/customers/{customerId}")
//    public ResponseEntity<?> updateCustomer(@PathVariable Long ownerId, @PathVariable Long customerId,
//                                            @Valid @RequestBody ParkingCustomerUpdateRequest request) {
//        return Result.success(ownerAccountProfileService.updateCustomer(ownerId, customerId, request));
//    }

    @PostMapping("/vehicles/{vehicleId}/users")
    public ResponseEntity<?> saveVehicleUser(@PathVariable Long vehicleId, @Valid @RequestBody VehicleUserSaveRequest request) {
        return Result.success(ownerAccountProfileService.saveVehicleUser(vehicleId, request));
    }

//    @DeleteMapping("/vehicles/{vehicleId}/users/{customerId}")
//    public ResponseEntity<?> removeVehicleUser(@PathVariable Long vehicleId, @PathVariable Long customerId) {
//        ownerAccountProfileService.removeVehicleUser(vehicleId, customerId);
//        return Result.success(customerId);
//    }

//    @PutMapping("/{id}")
//    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody OwnerAccountRequest request) {
//        return Result.success(ownerAccountService.update(id, request));
//    }

    private ResponseEntity<?> created(Object result) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new Result<>(201, "success", result));
    }
}
