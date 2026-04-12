package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.UserDevicePermissionEntity;

public interface UserDevicePermissionRepository extends JpaRepository<UserDevicePermissionEntity, Long> {
}