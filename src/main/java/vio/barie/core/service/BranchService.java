package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.CommonStatus;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.filter.UserContext;
import vio.barie.core.dto.branch.BranchCreateRequest;
import vio.barie.core.dto.branch.BranchPaginationRequest;
import vio.barie.core.dto.branch.BranchResponse;
import vio.barie.core.dto.branch.BranchUpdateRequest;
import vio.barie.core.dto.user.UserDTO;
import vio.barie.core.entity.BranchEntity;
import vio.barie.core.entity.OrganizeEntity;
import vio.barie.core.repository.BranchRepository;
import vio.barie.core.repository.BranchSpecification;
import vio.barie.core.repository.SysUserRepository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import static vio.barie.core.entity.BranchEntity.toResponse;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final SysUserRepository userRepository;

    public List<BranchResponse> getAllBranches() {
        return branchRepository.findAll().stream()
                .map(branch -> BranchResponse.builder()
                        .id(branch.getId())
                        .organizeId(branch.getOrganize().getId()) // Giả sử Entity cũng có các field tương ứng
                        .organizeName(String.valueOf(branch.getOrganize()))
                        .branchName(branch.getBranchName())
                        .branchCode(branch.getBranchCode())
                        .address(branch.getAddress())
                        .description(branch.getDescription())
                        .province(branch.getProvince())
                        .status(branch.getStatus())
                        .createdAt(branch.getCreatedAt())
                        .updatedAt(branch.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BranchResponse getById(Long id) {
        UserDTO user = UserContext.get();
        Long organizeId = user.getOrganizeId();
        BranchEntity branch = branchRepository.findByIdAndOrganize_Id(id, organizeId).orElseThrow(() -> new ServiceException("Branch not found", 404));
        return toResponse(branch);
    }

    @Transactional(readOnly = true)
    public Page<BranchResponse> list(BranchPaginationRequest paginationRequest) {
        UserDTO user = UserContext.get();
        Long organizeId = user.getOrganizeId();

        if (user.hasOnlyRoleBranchEdit() || user.hasOnlyRoleBranchView()) {
            return branchRepository.findByIdAndOrganize_Id(user.getBranchId(), organizeId)
                    .map(BranchEntity::toResponse)
                    .map(branchResponse -> new PageImpl<>(
                            List.of(branchResponse),
                            PageRequest.of(paginationRequest.getPage(), paginationRequest.getSize()),
                            1
                    ))
                    .orElseGet(() -> new PageImpl<>(
                            Collections.emptyList(),
                            PageRequest.of(paginationRequest.getPage(), paginationRequest.getSize()),
                            0
                    ));
        }

        Sort.Direction direction = Sort.Direction.fromString(paginationRequest.getSortDirection().toUpperCase());
        Sort sort = Sort.by(direction, paginationRequest.getSortBy());
        Pageable pageable = PageRequest.of(paginationRequest.getPage(), paginationRequest.getSize(), Sort.by("createdAt").descending());

        return branchRepository.findAll(
                BranchSpecification.filterByOrganizeIdAndFilters(
                        organizeId,
                        paginationRequest.getBranchName(),
                        paginationRequest.getSearchTerm(),
                        paginationRequest.getStatus(),
                        paginationRequest.getProvince()),
                pageable).map(BranchEntity::toResponse);
    }

    @Transactional
    public BranchResponse create(BranchCreateRequest request) {
        UserDTO user = UserContext.get();
        Long organizeId = UserContext.getOrgId();
        String branchCode = request.getBranchCode().trim();
//        if (!user.hasAnyRole(RoleEnum.ONLY_ORG_EDIT()))
//            throw new ServiceException("Forbidden", 403);
//
        if (branchRepository.existsByOrganizeIdAndBranchCodeIgnoreCase(organizeId, branchCode)) {
            throw new ServiceException("Branch code already exists for this organize", 409);
        }
        BranchEntity entity = new BranchEntity();
        OrganizeEntity org = new OrganizeEntity();
        org.setId(organizeId);
        entity.setOrganize(org);
        entity.setDescription(StringUtils.isBlank(request.getDescription()) ? null : request.getDescription().trim());
        entity.setBranchCode(branchCode);
        entity.setAddress(request.getAddress().trim());
        entity.setProvince(request.getProvince().trim());
        entity.setStatus(CommonStatus.valueOf(request.getStatus()));

        entity.setManagerName(StringUtils.isBlank(request.getManagerName()) ? null : request.getManagerName().trim());
        entity.setBranchName(request.getBranchName().trim());
        entity.setLatitude(request.getLatitude());
        entity.setLongitude(request.getLongitude());

        return toResponse(branchRepository.save(entity));
    }

    @Transactional
    public BranchResponse update(BranchUpdateRequest request, Long id) {
        UserDTO user = UserContext.get();
        BranchEntity entity = branchRepository.findByIdAndOrganize_Id(id, user.getOrganizeId()).orElseThrow(() -> new ServiceException("Branch not found", 404));
        String newCode = request.getBranchCode().trim();
        if (branchRepository.existsByOrganizeIdAndBranchCodeIgnoreCaseAndIdNot(user.getOrganizeId(), newCode, id)) {
            throw new ServiceException("Branch code already exists for this organize", 409);
        }

        if (user.hasOnlyRoleBranchEdit()) {
            if (!user.getBranchId().equals(id))
                throw new ServiceException("Forbidden", 403); // Branch edit role can only edit their own branch
            if (entity.getStatus().equals(CommonStatus.DELETED))
                throw new ServiceException("Cannot edit a deleted branch", 400);
        }
        entity.setBranchCode(newCode);
        entity.setDescription(StringUtils.isBlank(request.getDescription()) ? null : request.getDescription().trim());
        entity.setStatus(CommonStatus.valueOf(request.getStatus()));
        entity.setAddress(request.getAddress().trim());
        entity.setProvince(request.getProvince().trim());
        entity.setManagerName(StringUtils.isBlank(request.getManagerName()) ? null : request.getManagerName().trim());
        entity.setBranchName(request.getBranchName().trim());
        entity.setLatitude(request.getLatitude());
        entity.setLongitude(request.getLongitude());
        return toResponse(branchRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        UserDTO user = UserContext.get();
        validActionDeleteBranch(id);
        BranchEntity entity = branchRepository.findByIdAndOrganize_Id(id, user.getOrganizeId()).orElseThrow(() -> new ServiceException("Branch not found", 404));
        branchRepository.delete(entity);
    }

    private void validActionDeleteBranch(Long branchId) {
        if (userRepository.existsByBranch_Id(branchId)) {
            throw new ServiceException("Vui lòng xóa tất cả user trong chi nhánh trước khi xóa chi nhánh");
        }
    }
//
//    private BranchResponse toResponse(BranchEntity b) {
//        OrganizeEntity org = b.getOrganize();
//        return BranchResponse.builder().latitude(b.getLatitude()).longitude(b.getLongitude()).id(b.getId()).branchName(b.getBranchName()).managerName(b.getManagerName()).province(b.getProvince()).description(b.getDescription()).organizeId(org.getId()).organizeName(org.getName()).branchCode(b.getBranchCode()).address(b.getAddress()).status(b.getStatus()).createdAt(b.getCreatedAt()).updatedAt(b.getUpdatedAt()).build();
//    }

}
