package vio.barie.core.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.dto.VehicleSubscriptionResponse;
import vio.barie.core.entity.VehicleSubscriptionEntity;

import java.time.LocalDateTime;

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
}
