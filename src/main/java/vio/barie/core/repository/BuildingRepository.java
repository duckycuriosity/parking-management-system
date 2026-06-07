package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.entity.BuildingEntity;

import java.util.List;

public interface BuildingRepository extends JpaRepository<BuildingEntity, Long> {
    List<BuildingEntity> findByStatus(BaseStatus status);
}