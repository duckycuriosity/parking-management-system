package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.ParkingRuleEntity;
import vio.barie.core.entity.SysUserEntity;

public interface ParkingRuleRepository extends JpaRepository<ParkingRuleEntity, Long> {
}