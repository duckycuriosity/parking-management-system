package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.CameraEntity;

import java.util.Optional;

public interface CameraRepository extends JpaRepository<CameraEntity, Long> {
    @Query("SELECT c FROM CameraEntity c JOIN FETCH c.lane WHERE c.id = :id")
    Optional<CameraEntity> findByIdWithLane(@Param("id") Long id);

    @Query("SELECT c FROM CameraEntity c JOIN FETCH c.lane WHERE c.id = :cameraCode")
    Optional<CameraEntity> findByCodeWithLane(@Param("cameraCode") String cameraCode);

    @Query("SELECT c FROM CameraEntity c WHERE c.aiCameraId = :aiCameraId")
    Optional<CameraEntity> findCameraByAiCameraId(@Param("aiCameraId") Long aiCameraId);
}