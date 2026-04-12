package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.SysUserEntity;

public interface ParkingCustomerRepository extends JpaRepository<ParkingCustomerEntity, Long> {
}