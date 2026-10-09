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
    @Query(value = """
            SELECT 
                vs.id AS subscriptionId,
                v.plate_number AS plateNumber,
                pc.username AS subscriberName,
                pl.name AS parkingLotName,
                vs.ends_at AS expirationDate,
                sp.name AS ticketType,
                vs.status AS status
            FROM vehicle_subscription vs
            INNER JOIN parking_lot pl ON vs.parking_lot_id = pl.id
            INNER JOIN subscription_package sp ON vs.package_id = sp.id
            INNER JOIN vehicle v ON vs.vehicle_id = v.id
            LEFT JOIN vehicle_customer vc ON v.id = vc.vehicle_id AND vc.status = 'ACTIVE'
            LEFT JOIN parking_customer pc ON vc.customer_id = pc.id
            WHERE 
                (:keyword IS NULL OR v.plate_number ILIKE CONCAT('%', :keyword, '%') OR pc.username ILIKE CONCAT('%', :keyword, '%'))
                AND (:buildingId IS NULL OR pl.building_id = :buildingId)
                AND (:parkingLotId IS NULL OR vs.parking_lot_id = :parkingLotId)
                AND (CAST(:startDate AS timestamp) IS NULL OR vs.ends_at >= :startDate)
                AND (CAST(:endDate AS timestamp) IS NULL OR vs.ends_at <= :endDate)
            """,
            countQuery = """
            SELECT count(vs.id) 
            FROM vehicle_subscription vs
            INNER JOIN parking_lot pl ON vs.parking_lot_id = pl.id
            INNER JOIN vehicle v ON vs.vehicle_id = v.id
            LEFT JOIN vehicle_customer vc ON v.id = vc.vehicle_id AND vc.status = 'ACTIVE'
            LEFT JOIN parking_customer pc ON vc.customer_id = pc.id
            WHERE 
                (:keyword IS NULL OR v.plate_number ILIKE CONCAT('%', :keyword, '%') OR pc.username ILIKE CONCAT('%', :keyword, '%'))
                AND (:buildingId IS NULL OR pl.building_id = :buildingId)
                AND (:parkingLotId IS NULL OR vs.parking_lot_id = :parkingLotId)
                AND (CAST(:startDate AS timestamp) IS NULL OR vs.ends_at >= :startDate)
                AND (CAST(:endDate AS timestamp) IS NULL OR vs.ends_at <= :endDate)
            """,
            nativeQuery = true)
    Page<VehicleSubscriptionResponse> findSubscriptionDetails(
            @Param("keyword") String keyword,
            @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

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
