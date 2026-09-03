package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.CameraEventLogEntity;

public interface CameraEventLogRepository extends JpaRepository<CameraEventLogEntity, String> {
}
