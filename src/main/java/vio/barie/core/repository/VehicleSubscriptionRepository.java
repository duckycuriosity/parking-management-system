package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Domain-scoped API: no bulk update/delete methods that bypass lifecycle guards. */
public interface VehicleSubscriptionRepository extends Repository<VehicleSubscriptionEntity, Long> {

    /** One latest order per vehicle; callers batch user names separately. */
    @Query("""
            select s.vehicleId as vehicleId, lot.name as parkingLotName,
                   s.registrationType as registrationTypeValue, s.status as statusValue,
                   s.startsAt as validFromInstant, s.endsAt as validToInstant,
                   case when s.endsAt < current_timestamp then true else false end as expired
            from VehicleSubscriptionEntity s
            left join ParkingLotEntity lot on lot.id = s.parkingLotId
            where s.vehicleId in :vehicleIds
              and not exists (
                select newer.id from VehicleSubscriptionEntity newer
                where newer.vehicleId = s.vehicleId
                  and (newer.createdAt > s.createdAt
                    or (newer.createdAt = s.createdAt and newer.id > s.id))
              )
            """)
    List<RegisteredVehicleSubscriptionProjection> findLatestRegisteredVehicleSubscriptions(
            @Param("vehicleIds") List<Long> vehicleIds);

    interface RegisteredVehicleSubscriptionProjection {
        Long getVehicleId();
        String getParkingLotName();
        vio.barie.core.common.SubscriptionRegistrationType getRegistrationTypeValue();
        vio.barie.core.common.VehicleSubscriptionStatus getStatusValue();
        Instant getValidFromInstant();
        Instant getValidToInstant();
        boolean getExpired();

        default String getTicketType() {
            return getRegistrationTypeValue() == null ? null : getRegistrationTypeValue().name();
        }
        default String getSubscriptionStatus() {
            return getStatusValue() == null ? null : getStatusValue().name();
        }
        default OffsetDateTime getValidFrom() {
            return getValidFromInstant() == null ? null : getValidFromInstant().atOffset(ZoneOffset.UTC);
        }
        default OffsetDateTime getValidTo() {
            return getValidToInstant() == null ? null : getValidToInstant().atOffset(ZoneOffset.UTC);
        }
    }

    Optional<VehicleSubscriptionEntity> findById(Long id);

    /** Response graph for a bounded aggregate result, never used by invariant checks. */
    @EntityGraph(attributePaths = {"subscriptionPackage", "subscriptionPackage.schedules"})
    @Query("select distinct s from VehicleSubscriptionEntity s where s.id in :ids")
    List<VehicleSubscriptionEntity> findWithSchedulesByIds(@Param("ids") List<Long> ids);

    <S extends VehicleSubscriptionEntity> S save(S subscription);

    <S extends VehicleSubscriptionEntity> S saveAndFlush(S subscription);

    // Lock only the existing parent row. The same lock serializes the first purchase,
    // renewal, callback and checkout even when no subscription row exists yet.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VehicleEntity v where v.id = :vehicleId")
    Optional<VehicleEntity> lockVehicleEntity(@Param("vehicleId") Long vehicleId);

    default Optional<Long> lockVehicle(Long vehicleId) {
        return lockVehicleEntity(vehicleId).map(VehicleEntity::getId);
    }

    // Fetching a package/schedule collection while locking can generate an outer
    // join that PostgreSQL cannot lock. Keep row locks separate from DTO reads.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from VehicleSubscriptionEntity s where s.id = :subscriptionId")
    Optional<VehicleSubscriptionEntity> lockSubscriptionEntity(@Param("subscriptionId") Long subscriptionId);

    default Optional<Long> lockSubscription(Long subscriptionId) {
        return lockSubscriptionEntity(subscriptionId).map(VehicleSubscriptionEntity::getId);
    }

    @Query("select v.vehicleType from VehicleEntity v where v.id = :vehicleId")
    Optional<String> findVehicleType(@Param("vehicleId") Long vehicleId);

    @Query("select (count(p) > 0) from ParkingLotEntity p where p.id = :parkingLotId")
    boolean parkingLotExists(@Param("parkingLotId") Long parkingLotId);

    Optional<VehicleSubscriptionEntity> findByRequestKey(String requestKey);

    // These methods explicitly serve response/coverage reads, where schedules are
    // required. Invariant/existence checks below never load that collection graph.
    @EntityGraph(attributePaths = {"subscriptionPackage", "subscriptionPackage.schedules"})
    List<VehicleSubscriptionEntity> findByVehicleIdAndParkingLotIdOrderByStartsAtAscIdAsc(
            Long vehicleId, Long parkingLotId);

    List<VehicleSubscriptionEntity> findByPreviousSubscriptionIdOrderByIdAsc(Long previousSubscriptionId);

    @Query("""
            select (count(s) > 0) from VehicleSubscriptionEntity s
            where s.vehicleId = :vehicleId and s.parkingLotId = :parkingLotId
              and s.status = vio.barie.core.common.VehicleSubscriptionStatus.PAID
              and s.startsAt < :until and s.endsAt > :from
            """)
    boolean existsPaidOverlapping(@Param("vehicleId") Long vehicleId,
                                  @Param("parkingLotId") Long parkingLotId,
                                  @Param("from") Instant from,
                                  @Param("until") Instant until);

    @Query("""
            select s from VehicleSubscriptionEntity s
            where s.vehicleId = :vehicleId and s.parkingLotId = :parkingLotId
              and s.status = vio.barie.core.common.VehicleSubscriptionStatus.PAID
              and s.startsAt < :until and s.endsAt > :from
            order by s.startsAt asc, s.id asc
            """)
    List<VehicleSubscriptionEntity> findPaidOverlapping(@Param("vehicleId") Long vehicleId,
                                                       @Param("parkingLotId") Long parkingLotId,
                                                       @Param("from") Instant from,
                                                       @Param("until") Instant until);

    @EntityGraph(attributePaths = {"subscriptionPackage", "subscriptionPackage.schedules"})
    @Query("""
            select s from VehicleSubscriptionEntity s
            where s.vehicleId = :vehicleId and s.parkingLotId = :parkingLotId
              and s.status = vio.barie.core.common.VehicleSubscriptionStatus.PAID
              and s.startsAt < :until and s.endsAt > :from
            order by s.startsAt asc, s.id asc
            """)
    List<VehicleSubscriptionEntity> findPaidOverlappingWithPackageSchedules(@Param("vehicleId") Long vehicleId,
                                                       @Param("parkingLotId") Long parkingLotId,
                                                       @Param("from") Instant from,
                                                       @Param("until") Instant until);

    @EntityGraph(attributePaths = {"subscriptionPackage", "subscriptionPackage.schedules"})
    @Query("""
            select s from VehicleSubscriptionEntity s
            where s.vehicleId = :vehicleId and s.parkingLotId = :parkingLotId
              and s.status = vio.barie.core.common.VehicleSubscriptionStatus.PAID
              and s.startsAt <= :at and :at < s.endsAt
            order by s.startsAt asc, s.id asc
            """)
    List<VehicleSubscriptionEntity> findPaidAt(@Param("vehicleId") Long vehicleId,
                                              @Param("parkingLotId") Long parkingLotId,
                                              @Param("at") Instant at);
}
