package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.SysUserEntity;

public interface LaneRepository extends JpaRepository<LaneEntity, Long> {

}