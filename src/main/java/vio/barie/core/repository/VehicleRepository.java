package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.VehicleEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    Optional<VehicleEntity> findByPlateNumber(String plateNumber);

    Optional<VehicleEntity> findByAiIdentityPlateId(String aiIdentityPlateId);

    boolean existsByPlateNumber(String plateNumber);

    boolean existsByPlateNumberAndIdNot(String plateNumber, Long id);

    long countByOwnerId(Long ownerId);

    Optional<VehicleEntity> findByIdAndOwnerId(Long id, Long ownerId);

    VehicleEntity findPlateByPlateNumber(String plateNumber);

    VehicleEntity findFirstByPlateNumberOrderByIdDesc(String plateNumber);

    Optional<VehicleEntity> findVehicleById(Long id);

    @Query(
            value = """
                    SELECT
                        v.id AS "vehicleId",
                        v.plate_number AS "plate",
                        v.vehicle_type AS "vehicleType",
                        v.status AS "vehicleStatus",
                        COALESCE(STRING_AGG(DISTINCT pc.username, ', ') FILTER (WHERE pc.username IS NOT NULL), '') AS "userName",
                        pl.name AS "parkingLotName",
                        b.name AS "buildingName",
                        pr.name AS "packageName",
                        pr.type AS "packageType",
                        vs.registration_type AS "ticketType",
                        vs.status AS "subscriptionStatus",
                        vs.starts_at AS "validFrom",
                        vs.ends_at AS "validTo",
                        CASE WHEN vs.ends_at IS NOT NULL AND vs.ends_at < CURRENT_TIMESTAMP THEN TRUE ELSE FALSE END AS "expired"
                    FROM vehicle v
                    LEFT JOIN LATERAL (
                        SELECT latest_vs.*
                        FROM vehicle_subscription latest_vs
                        WHERE latest_vs.vehicle_id = v.id
                        ORDER BY latest_vs.created_at DESC, latest_vs.id DESC
                        LIMIT 1
                    ) vs ON TRUE
                    LEFT JOIN parking_lot pl ON pl.id = vs.parking_lot_id
                    LEFT JOIN building b ON b.id = pl.building_id
                    LEFT JOIN pricing_rule pr ON pr.id = vs.package_id
                    LEFT JOIN vehicle_customer vc ON vc.vehicle_id = v.id
                    LEFT JOIN parking_customer pc ON pc.id = vc.customer_id
                    WHERE v.owner_id = :ownerId
                    GROUP BY v.id, v.plate_number, v.vehicle_type, v.status, pl.name, b.name, pr.name, pr.type,
                        vs.registration_type, vs.status, vs.starts_at, vs.ends_at, v.created_at
                    ORDER BY v.created_at DESC, v.id DESC
                    """,
            nativeQuery = true
    )
    List<RegisteredVehicleProjection> findRegisteredVehiclesByOwnerId(@Param("ownerId") Long ownerId);

    interface RegisteredVehicleProjection {
        Long getVehicleId();

        String getPlate();

        String getVehicleType();

        String getVehicleStatus();

        String getUserName();

        String getParkingLotName();

        String getBuildingName();

        String getPackageName();

        String getPackageType();

        String getTicketType();

        String getSubscriptionStatus();

        Instant getValidFrom();

        Instant getValidTo();

        boolean getExpired();
    }
}
