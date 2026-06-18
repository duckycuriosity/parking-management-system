package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.entity.BuildingEntity;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BuildingRepository extends JpaRepository<BuildingEntity, Long> {
    List<BuildingEntity> findByStatus(BaseStatus status);

    @Query("SELECT b.name FROM BuildingEntity b WHERE (:excludeId IS NULL OR b.id != :excludeId)")
    List<String> findAllNamesExcludingId(@Param("excludeId") Long excludeId);
}