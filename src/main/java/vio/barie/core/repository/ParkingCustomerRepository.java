package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.SysUserEntity;

import java.util.Optional;

public interface ParkingCustomerRepository extends JpaRepository<ParkingCustomerEntity, Long> {
    Optional<ParkingCustomerEntity> findByAiIdentityId(String aiIdentityId);

    Optional<ParkingCustomerEntity> findByAiProfileId(Integer aiProfileId);

    Optional<ParkingCustomerEntity> findByPhoneNumber(String phoneNumber);

    Optional<ParkingCustomerEntity> findParkingCustomerByUsernameAndPhoneNumber(String username, String phoneNumber);
}