package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountPaginationRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountResponse;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.repository.OwnerAccountRepository;
import vio.barie.core.repository.OwnerAccountSpecification;


import java.util.Set;

@Service
@RequiredArgsConstructor
public class OwnerAccountService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "fullName", "phone", "identityNumber", "status", "createdAt", "updatedAt"
    );

    private final OwnerAccountRepository ownerAccountRepository;

    @Transactional(readOnly = true)
    public OwnerAccountResponse getById(Long id) {
        return toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<OwnerAccountResponse> list(OwnerAccountPaginationRequest request) {
        if (request.getFromDate() != null && request.getToDate() != null && request.getFromDate().isAfter(request.getToDate())) {
            throw new ServiceException("fromDate must be before or equal to toDate", 400);
        }

        int page = Math.max(request.getPage(), 0);
        int size = request.getSize() <= 0 ? 20 : Math.min(request.getSize(), 100);
        Sort.Direction direction = resolveDirection(request.getSortDirection());
        String sortBy = resolveSortBy(request.getSortBy());
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        return ownerAccountRepository.findAll(
                OwnerAccountSpecification.filter(
                        request.getSearchTerm(),
                        request.getStatus(),
                        request.getFromDate(),
                        request.getToDate()
                ),
                pageable
        ).map(this::toResponse);
    }

    @Transactional
    public OwnerAccountResponse create(OwnerAccountRequest request) {
        OwnerAccountEntity entity = new OwnerAccountEntity();
        applyRequest(entity, request);
        return toResponse(ownerAccountRepository.save(entity));
    }

    @Transactional
    public OwnerAccountResponse update(Long id, OwnerAccountRequest request) {
        OwnerAccountEntity entity = findEntity(id);
        applyRequest(entity, request);
        return toResponse(ownerAccountRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        OwnerAccountEntity entity = findEntity(id);
        ownerAccountRepository.delete(entity);
    }

    private OwnerAccountEntity findEntity(Long id) {
        return ownerAccountRepository.findById(id)
                .orElseThrow(() -> new ServiceException("Owner account not found", 404));
    }

    private void applyRequest(OwnerAccountEntity entity, OwnerAccountRequest request) {
        entity.setFullName(request.getFullName().trim());
        entity.setPhone(request.getPhone().trim());
        entity.setIdentityNumber(trimToNull(request.getIdentityNumber()));
        entity.setAddress(trimToNull(request.getAddress()));
        entity.setImageUrl(trimToNull(request.getImageUrl()));
        entity.setZaloLink(trimToNull(request.getZaloLink()));
        entity.setNote(trimToNull(request.getNote()));
        entity.setStatus(request.getStatus().trim());
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private Sort.Direction resolveDirection(String sortDirection) {
        if (StringUtils.isBlank(sortDirection)) {
            return Sort.Direction.DESC;
        }
        try {
            return Sort.Direction.fromString(sortDirection.trim());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("sortDirection must be ASC or DESC", 400);
        }
    }

    private String resolveSortBy(String sortBy) {
        if (StringUtils.isBlank(sortBy)) {
            return "createdAt";
        }
        String trimmedSortBy = sortBy.trim();
        return ALLOWED_SORT_FIELDS.contains(trimmedSortBy) ? trimmedSortBy : "createdAt";
    }

    private OwnerAccountResponse toResponse(OwnerAccountEntity entity) {
        return OwnerAccountResponse.builder()
                .id(entity.getId())
                .fullName(entity.getFullName())
                .phone(entity.getPhone())
                .identityNumber(entity.getIdentityNumber())
                .address(entity.getAddress())
                .imageUrl(entity.getImageUrl())
                .zaloLink(entity.getZaloLink())
                .note(entity.getNote())
                .status(entity.getStatus())
                .vehicleCount(ownerAccountRepository.countVehiclesByOwnerId(entity.getId()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
