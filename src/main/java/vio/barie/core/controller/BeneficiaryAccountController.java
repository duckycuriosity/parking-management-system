package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.dto.BeneficiaryAccountRequest;
import vio.barie.core.dto.BeneficiaryAccountResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.service.BeneficiaryAccountService;
@RestController
@RequestMapping("/api/v1/beneficiary-accounts")
@RequiredArgsConstructor
public class BeneficiaryAccountController {
    private final BeneficiaryAccountService service;

    @GetMapping
    public Result<Page<BeneficiaryAccountResponse>> getAll(
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long parkingLotId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BeneficiaryAccountResponse> response = service.getAccounts(buildingId, parkingLotId, status, pageable);
        return Result.ok("Lấy danh sách tài khoản thụ hưởng thành công.", response);
    }

    @GetMapping("/{id}")
    public Result<BeneficiaryAccountResponse> getById(@PathVariable Long id) {
        BeneficiaryAccountResponse response = service.getAccountById(id);
        return Result.ok("Lấy chi tiết tài khoản thụ hưởng thành công.", response);
    }

    @PostMapping
    public Result<BeneficiaryAccountResponse> create(
            @Valid @RequestBody BeneficiaryAccountRequest request) {

        BeneficiaryAccountResponse response = service.createAccount(request);
        return Result.ok("Tạo tài khoản thụ hưởng thành công.", response);
    }

    @PutMapping("/{id}")
    public Result<BeneficiaryAccountResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody BeneficiaryAccountRequest request) {

        BeneficiaryAccountResponse response = service.updateAccount(id, request);
        return Result.ok("Cập nhật tài khoản thụ hưởng thành công .", response);
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {

        service.updateStatus(id, status);
        return Result.ok("Cập nhật trạng thái tài khoản thụ hưởng thành công.", null);
    }
}
