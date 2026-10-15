package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vio.barie.core.entity.SubscriptionPackageEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionPackageRepository extends JpaRepository<SubscriptionPackageEntity, Long> {

    Optional<SubscriptionPackageEntity> findFirstById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SubscriptionPackageEntity> findTopById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SubscriptionPackageEntity> findFirstByPackageCodeOrderByVersionNoAscIdAsc(String packageCode);

    Optional<SubscriptionPackageEntity> findFirstByPackageCodeOrderByVersionNoDescIdDesc(String packageCode);

    long countByPackageCodeAndIdNotAndStatusAndEffectiveFromBeforeAndEffectiveToIsNull(
            String packageCode, Long excludedId, String status, LocalDateTime until);

    long countByPackageCodeAndIdNotAndStatusAndEffectiveFromBeforeAndEffectiveToGreaterThan(
            String packageCode, Long excludedId, String status,
            LocalDateTime until, LocalDateTime from);

    long countByPackageCodeAndIdNotAndStatusAndEffectiveToIsNull(
            String packageCode, Long excludedId, String status);

    long countByPackageCodeAndIdNotAndStatusAndEffectiveToGreaterThan(
            String packageCode, Long excludedId, String status, LocalDateTime from);

    List<SubscriptionPackageEntity> findByPackageCodeOrderByVersionNoAsc(String packageCode);

    List<SubscriptionPackageEntity> findByParkingLotIdAndVehicleTypeAndStatusAndSaleEnabledTrueAndEffectiveFromLessThanEqualAndEffectiveToIsNullOrderByPackageCodeAscVersionNoAsc(
            Long parkingLotId, String vehicleType, String status, LocalDateTime at);

    List<SubscriptionPackageEntity> findByParkingLotIdAndVehicleTypeAndStatusAndSaleEnabledTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanOrderByPackageCodeAscVersionNoAsc(
            Long parkingLotId, String vehicleType, String status,
            LocalDateTime from, LocalDateTime at);
}
