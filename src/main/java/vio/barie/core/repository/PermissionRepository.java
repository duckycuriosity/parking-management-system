package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.PermissionEntity;

public interface PermissionRepository extends JpaRepository<PermissionEntity, Long> {
}
