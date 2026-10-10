package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.SysUserEntity;

import java.util.Optional;

public interface ParkingCustomerRepository extends JpaRepository<ParkingCustomerEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ParkingCustomerEntity c where c.id = :id and c.ownerId = :ownerId")
    Optional<ParkingCustomerEntity> findOwnedForUpdate(@Param("id") Long id, @Param("ownerId") Long ownerId);
    Optional<ParkingCustomerEntity> findByAiIdentityId(String aiIdentityId);

    Optional<ParkingCustomerEntity> findByAiProfileId(Integer aiProfileId);

    boolean existsByAiProfileId(Integer aiProfileId);

    Optional<ParkingCustomerEntity> findByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumberAndIdNot(String phoneNumber, Long id);

    Optional<ParkingCustomerEntity> findByIdAndOwnerId(Long id, Long ownerId);

    ParkingCustomerEntity findCustomerById (Long id);

    Optional<ParkingCustomerEntity> findParkingCustomerByUsernameAndPhoneNumber(String username, String phoneNumber);
}
