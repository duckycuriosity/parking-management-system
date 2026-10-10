package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionPackageRepository extends Repository<SubscriptionPackageEntity, Long> {

    Optional<SubscriptionPackageEntity> findById(Long id);

    /** No delete or bulk-update API: catalog versions and family lock anchors are retained. */
    <S extends SubscriptionPackageEntity> S saveAndFlush(S entity);

    // Keep sale availability stable while a quote is persisted, without
    // serializing purchases from different vehicles against the same catalog row.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from SubscriptionPackageEntity p where p.id = :packageId")
    Optional<SubscriptionPackageEntity> lockPackage(@Param("packageId") Long packageId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SubscriptionPackageEntity p where p.id = :packageId")
    Optional<SubscriptionPackageEntity> lockForManagement(@Param("packageId") Long packageId);

    /** Stable family anchor; also supports historical families whose first version was not 1. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SubscriptionPackageEntity> findFirstByPackageCodeOrderByVersionNoAscIdAsc(String packageCode);

    @Query("select max(p.versionNo) from SubscriptionPackageEntity p where p.packageCode = :code")
    Optional<Integer> maximumVersion(@Param("code") String code);

    @Query("""
            select (count(p) > 0) from SubscriptionPackageEntity p
            where p.packageCode = :code and p.id <> :excludingId
              and p.status = vio.barie.core.common.SubscriptionPackageStatus.PUBLISHED
              and p.effectiveFrom < :until
              and (p.effectiveTo is null or :from < p.effectiveTo)
            """)
    boolean publishedOverlapUntil(@Param("code") String code, @Param("excludingId") Long excludingId,
                                  @Param("from") Instant from, @Param("until") Instant until);

    @Query("""
            select (count(p) > 0) from SubscriptionPackageEntity p
            where p.packageCode = :code and p.id <> :excludingId
              and p.status = vio.barie.core.common.SubscriptionPackageStatus.PUBLISHED
              and (p.effectiveTo is null or :from < p.effectiveTo)
            """)
    boolean publishedOverlapWithoutEnd(@Param("code") String code, @Param("excludingId") Long excludingId,
                                       @Param("from") Instant from);

    /** Separate SQL shapes avoid an untyped PostgreSQL parameter in ':until is null'. */
    default boolean publishedOverlap(String code, Long excludingId, Instant from, Instant until) {
        return until == null ? publishedOverlapWithoutEnd(code, excludingId, from)
                : publishedOverlapUntil(code, excludingId, from, until);
    }

    @EntityGraph(attributePaths = "schedules")
    @Query("select p from SubscriptionPackageEntity p where p.id = :id")
    Optional<SubscriptionPackageEntity> findWithSchedules(@Param("id") Long id);

    @EntityGraph(attributePaths = "schedules")
    List<SubscriptionPackageEntity> findByPackageCodeOrderByVersionNoAsc(String packageCode);

    @EntityGraph(attributePaths = "schedules")
    @Query("""
            select p from SubscriptionPackageEntity p
            where p.parkingLotId = :parkingLotId
              and p.vehicleType = :vehicleType
              and p.status = vio.barie.core.common.SubscriptionPackageStatus.PUBLISHED
              and p.saleEnabled = true
              and p.effectiveFrom <= :at
              and (p.effectiveTo is null or :at < p.effectiveTo)
            order by p.packageCode asc, p.versionNo asc
            """)
    List<SubscriptionPackageEntity> findPublishedAt(@Param("parkingLotId") Long parkingLotId,
                                                  @Param("vehicleType") VehicleType vehicleType,
                                                  @Param("at") Instant at);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ParkingLotEntity p where p.id = :parkingLotId")
    Optional<ParkingLotEntity> lockParkingLotEntity(@Param("parkingLotId") Long parkingLotId);

    default Optional<Long> lockParkingLot(Long parkingLotId) {
        return lockParkingLotEntity(parkingLotId).map(ParkingLotEntity::getId);
    }

    @Query("select (count(p) > 0) from ParkingLotEntity p where p.id = :parkingLotId")
    boolean parkingLotExists(@Param("parkingLotId") Long parkingLotId);
}
