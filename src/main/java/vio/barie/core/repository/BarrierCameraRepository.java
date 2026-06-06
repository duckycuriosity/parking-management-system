package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vio.barie.core.entity.BarrierCameraEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface BarrierCameraRepository extends JpaRepository<BarrierCameraEntity, Long> {
    List<BarrierCameraEntity> findByCameraId(Long cameraId);

    List<BarrierCameraEntity> findByCameraIdAndRoleIgnoreCase(Long cameraId, String role);

    List<BarrierCameraEntity> findByBarrierId(String barrierId);

    void deleteByBarrierIdIn(List<String> barrierIds);

    List<BarrierCameraEntity> findByBarrierIdIn(List<String> barrierIds);

    Optional<BarrierCameraEntity> findFirstByCameraIdAndBarrierIdNot(Long cameraId, String barrierId);
}
