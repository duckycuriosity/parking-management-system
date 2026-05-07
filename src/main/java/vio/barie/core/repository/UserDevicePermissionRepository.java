package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.UserDevicePermissionEntity;

import java.util.List;

public interface UserDevicePermissionRepository extends JpaRepository<UserDevicePermissionEntity, Long> {

    @Query("SELECT p FROM UserDevicePermissionEntity p JOIN FETCH p.device WHERE p.parkingCustomer.id = :customerId")
    List<UserDevicePermissionEntity> findByParkingCustomerIdWithDevice(@Param("customerId") Long customerId);
}