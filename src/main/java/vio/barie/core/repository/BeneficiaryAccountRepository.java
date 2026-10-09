package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import vio.barie.core.entity.BeneficiaryAccountEntity;
@Repository
public interface BeneficiaryAccountRepository  extends
        JpaRepository<BeneficiaryAccountEntity, Long>,
        JpaSpecificationExecutor<BeneficiaryAccountEntity> {

}
