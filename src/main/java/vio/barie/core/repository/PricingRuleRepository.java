package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.PricingRuleEntity;

import java.math.BigDecimal;
import java.util.Optional;

public interface PricingRuleRepository extends JpaRepository<PricingRuleEntity, Long> {
    Optional<PricingRuleEntity> findByType(String type);
    Optional<PricingRuleEntity> findByPrice(BigDecimal type);

}
