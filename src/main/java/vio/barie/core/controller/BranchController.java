package vio.barie.core.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.branch.BranchCreateRequest;
import vio.barie.core.dto.branch.BranchPaginationRequest;
import vio.barie.core.dto.branch.BranchUpdateRequest;
import vio.barie.core.service.BranchService;

import java.util.List;

@RestController
@RequestMapping("/api/branch")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
public class BranchController {

    private final BranchService branchService;

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return Result.success(branchService.getById(id));
    }

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_VIEW())")
    @PostMapping("/list")
    public ResponseEntity<?> list(@Valid @RequestBody(required = false) BranchPaginationRequest paginationRequest) {
        if (paginationRequest == null) {
            paginationRequest = BranchPaginationRequest.builder().build();
        }
        Page<?> result = branchService.list(paginationRequest);
        return Result.success(result);
    }

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).ONLY_ORG_EDIT())")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody BranchCreateRequest request) {
        return Result.success(branchService.create(request));
    }

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).CAN_EDIT())")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @Valid @RequestBody BranchUpdateRequest request, @PathVariable Long id) {
        return Result.success(branchService.update(request, id));
    }

    @PreAuthorize("hasAnyAuthority(T(vio.barie.core.common.RoleEnum).ONLY_ORG_EDIT())")
    @DeleteMapping
    public ResponseEntity<?> delete(
            @RequestBody List<Long> ids) {
        if (ids.size() > 15) throw new ServiceException("Cannot delete more record at once", 400);
        for (Long id : ids) {
            branchService.delete(id);
        }
        return Result.success(ids);
    }


}
