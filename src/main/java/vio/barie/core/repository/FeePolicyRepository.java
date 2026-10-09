package vio.barie.core.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.FeePolicyEntity;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FeePolicyRepository extends JpaRepository<FeePolicyEntity, Integer> {

    // Lock the parent even before the first tariff exists; all writers use this gate.
    @Query(value = "select id from parking_lot where id = :parkingLotId for update", nativeQuery = true)
    Optional<Long> lockParkingLot(@Param("parkingLotId") Long parkingLotId);

    @Query(value = "select exists(select 1 from parking_lot where id = :parkingLotId)", nativeQuery = true)
    boolean parkingLotExists(@Param("parkingLotId") Long parkingLotId);

    @Override
    @EntityGraph(attributePaths = "timeBands")
    Optional<FeePolicyEntity> findById(Integer id);

    @EntityGraph(attributePaths = "timeBands")
    List<FeePolicyEntity> findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
            Long parkingLotId, VehicleType vehicleType);

    @EntityGraph(attributePaths = "timeBands")
    @Query("""
            select p from FeePolicyEntity p
            where p.parkingLotId = :parkingLotId
              and p.vehicleType = :vehicleType
              and p.effectiveFrom <= :at
              and (p.effectiveTo is null or :at < p.effectiveTo)
            order by p.effectiveFrom asc, p.versionNo asc
            """)
    List<FeePolicyEntity> findEffectiveAt(@Param("parkingLotId") Long parkingLotId,
                                        @Param("vehicleType") VehicleType vehicleType,
                                        @Param("at") Instant at);

    @EntityGraph(attributePaths = "timeBands")
    @Query("""
            select p from FeePolicyEntity p
            where p.parkingLotId = :parkingLotId
              and p.vehicleType = :vehicleType
              and p.effectiveFrom < :until
              and (p.effectiveTo is null or p.effectiveTo > :from)
            order by p.effectiveFrom asc, p.versionNo asc
            """)
    List<FeePolicyEntity> findOverlapping(@Param("parkingLotId") Long parkingLotId,
                                        @Param("vehicleType") VehicleType vehicleType,
                                        @Param("from") Instant from,
                                        @Param("until") Instant until);
}
