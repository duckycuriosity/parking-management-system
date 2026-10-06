package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import vio.barie.core.entity.OwnerAccountEntity;

public interface OwnerAccountRepository extends JpaRepository<OwnerAccountEntity, Long>, JpaSpecificationExecutor<OwnerAccountEntity> {

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    boolean existsByIdentityNumber(String identityNumber);

    boolean existsByIdentityNumberAndIdNot(String identityNumber, Long id);
}
