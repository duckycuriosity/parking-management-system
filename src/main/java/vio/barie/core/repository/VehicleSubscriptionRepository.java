package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.dto.VehicleSubscriptionResponse;
import vio.barie.core.entity.VehicleSubscriptionEntity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public interface VehicleSubscriptionRepository extends JpaRepository<VehicleSubscriptionEntity, Long> {
    @Query("""
            select s from VehicleSubscriptionEntity s
            where exists (select p.id from SubscriptionPackageEntity p where p.id = s.packageId)
              and exists (select lot.id from ParkingLotEntity lot where lot.id = s.parkingLotId
                    and (:buildingId is null or lot.building.id = :buildingId))
              and (:parkingLotId is null or s.parkingLotId = :parkingLotId)
              and (:startDate is null or s.endsAt >= :startDate)
              and (:endDate is null or s.endsAt <= :endDate)
              and exists (select v.id from VehicleEntity v where v.id = s.vehicleId and (
                    :keyword is null or lower(v.plateNumber) like :keyword escape '!'
                    or exists (select o.id from OwnerAccountEntity o where o.id = v.ownerId
                        and lower(o.fullName) like :keyword escape '!')
                    or exists (select r.id from VehicleCustomerEntity r, ParkingCustomerEntity c
                        where r.vehicleId = v.id and r.customerId = c.id and r.status = 'ACTIVE'
                        and lower(c.username) like :keyword escape '!')))
            """)
    Page<VehicleSubscriptionEntity> findForListing(
            @Param("keyword") String keyword, @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId,
            @Param("startDate") OffsetDateTime startDate, @Param("endDate") OffsetDateTime endDate,
            Pageable pageable);

    @Query("""
            SELECT
                vs.vehicleId AS vehicleId,
                pl.name AS parkingLotName,
                vs.registrationType AS ticketType,
                vs.status AS subscriptionStatus,
                vs.startsAt AS validFrom,
                vs.endsAt AS validTo,
                CASE WHEN vs.endsAt IS NOT NULL AND vs.endsAt < CURRENT_TIMESTAMP THEN TRUE ELSE FALSE END AS expired
            FROM VehicleSubscriptionEntity vs
            LEFT JOIN ParkingLotEntity pl ON pl.id = vs.parkingLotId
            WHERE vs.vehicleId IN :vehicleIds
              AND NOT EXISTS (
                  SELECT 1
                  FROM VehicleSubscriptionEntity newerVs
                  WHERE newerVs.vehicleId = vs.vehicleId
                    AND (
                        newerVs.createdAt > vs.createdAt
                        OR (newerVs.createdAt = vs.createdAt AND newerVs.id > vs.id)
                    )
              )
            """)
    List<RegisteredVehicleSubscriptionProjection> findLatestRegisteredVehicleSubscriptions(
            @Param("vehicleIds") List<Long> vehicleIds
    );

    interface RegisteredVehicleSubscriptionProjection {
        Long getVehicleId();

        String getParkingLotName();

        String getTicketType();

        String getSubscriptionStatus();

        OffsetDateTime getValidFrom();

        OffsetDateTime getValidTo();

        boolean getExpired();
    }
}
