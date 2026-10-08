package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.BeneficiaryAccountRequest;
import vio.barie.core.dto.BeneficiaryAccountResponse;
import vio.barie.core.entity.BeneficiaryAccountEntity;
import vio.barie.core.repository.BeneficiaryAccountRepository;

@Service
@RequiredArgsConstructor
public class BeneficiaryAccountService {
    private final BeneficiaryAccountRepository repository;
    @Transactional(readOnly = true)
    public Page<BeneficiaryAccountResponse> getAccounts(Long buildingId, Long parkingLotId, String status, Pageable pageable) {
        Specification<BeneficiaryAccountEntity> spec = Specification.where((root, query, cb) -> cb.conjunction());

        if (buildingId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("buildingId"), buildingId));
        }
        if (parkingLotId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("parkingLotId"), parkingLotId));
        }
        if (status != null && !status.isEmpty()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        return repository.findAll(spec, pageable).map(this::mapToResponse);
    }
    @Transactional(readOnly = true)
    public BeneficiaryAccountResponse getAccountById(Long id) {
        BeneficiaryAccountEntity account = repository.findById(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy tài khoản với ID: " + id, "ACCOUNT_NOT_FOUND", 404));
        return mapToResponse(account);
    }
    @Transactional
    public BeneficiaryAccountResponse createAccount(BeneficiaryAccountRequest request) {
        BeneficiaryAccountEntity account = new BeneficiaryAccountEntity();
        BeanUtils.copyProperties(request, account);
        account.setStatus("ACTIVE");
        BeneficiaryAccountEntity saved = repository.save(account);
        return mapToResponse(saved);
    }

    @Transactional
    public BeneficiaryAccountResponse updateAccount(Long id, BeneficiaryAccountRequest request) {
        BeneficiaryAccountEntity account = repository.findById(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy tài khoản với ID: " + id, "ACCOUNT_NOT_FOUND", 404));

        BeanUtils.copyProperties(request, account, "id", "status", "createdAt", "updatedAt");

        BeneficiaryAccountEntity updated = repository.save(account);
        return mapToResponse(updated);
    }
    @Transactional
    public void updateStatus(Long id, String status) {
        BeneficiaryAccountEntity account = repository.findById(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy tài khoản với ID: " + id, "ACCOUNT_NOT_FOUND", 404));
        account.setStatus(status);
        repository.save(account);
    }
    private BeneficiaryAccountResponse mapToResponse(BeneficiaryAccountEntity account) {
        BeneficiaryAccountResponse response = new BeneficiaryAccountResponse();
        BeanUtils.copyProperties(account, response);
        return response;
    }
}
