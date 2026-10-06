package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vio.barie.core.entity.OwnerAccountEntity;

import java.time.OffsetDateTime;

public interface OwnerAccountRepository extends JpaRepository<OwnerAccountEntity, Long>, JpaSpecificationExecutor<OwnerAccountEntity> {

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    boolean existsByIdentityNumber(String identityNumber);

    boolean existsByIdentityNumberAndIdNot(String identityNumber, Long id);

    @Query(
            value = """
                    SELECT
                        oa.id AS "ownerId",
                        oa.full_name AS "fullName",
                        oa.identity_number AS "customerCode",
                        oa.address AS "address",
                        oa.phone AS "phone",
                        oa.zalo_link AS "zaloLink",
                        oa.status AS "status",
                        COUNT(DISTINCT v.id) AS "registeredVehicleCount",
                        COUNT(DISTINCT CASE WHEN UPPER(COALESCE(v.vehicle_type, '')) = 'CAR' THEN v.id END) AS "carCount",
                        COUNT(DISTINCT CASE WHEN UPPER(COALESCE(v.vehicle_type, '')) IN ('MOTORBIKE', 'MOTOBIKE') THEN v.id END) AS "motorbikeCount",
                        COUNT(DISTINCT CASE WHEN UPPER(COALESCE(vs.status, '')) = 'EXPIRED' OR vs.ends_at < CURRENT_TIMESTAMP THEN vs.id END) AS "expiredSubscriptionCount"
                    FROM owner_account oa
                    LEFT JOIN vehicle v ON v.owner_id = oa.id
                    LEFT JOIN vehicle_subscription vs ON vs.vehicle_id = v.id
                    LEFT JOIN parking_lot pl ON pl.id = vs.parking_lot_id
                    LEFT JOIN building b ON b.id = pl.building_id
                    WHERE (:searchTerm IS NULL
                        OR LOWER(oa.full_name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(oa.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.identity_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.address, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
                      AND (:status IS NULL OR oa.status = :status)
                      AND (:buildingId IS NULL OR b.id = :buildingId)
                      AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                      AND (CAST(:fromDate AS timestamptz) IS NULL OR oa.created_at >= :fromDate)
                      AND (CAST(:toDate AS timestamptz) IS NULL OR oa.created_at <= :toDate)
                    GROUP BY oa.id, oa.full_name, oa.identity_number, oa.address, oa.phone, oa.zalo_link, oa.status, oa.created_at
                    ORDER BY oa.created_at DESC, oa.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(DISTINCT oa.id)
                    FROM owner_account oa
                    LEFT JOIN vehicle v ON v.owner_id = oa.id
                    LEFT JOIN vehicle_subscription vs ON vs.vehicle_id = v.id
                    LEFT JOIN parking_lot pl ON pl.id = vs.parking_lot_id
                    LEFT JOIN building b ON b.id = pl.building_id
                    WHERE (:searchTerm IS NULL
                        OR LOWER(oa.full_name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(oa.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.identity_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.address, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
                      AND (:status IS NULL OR oa.status = :status)
                      AND (:buildingId IS NULL OR b.id = :buildingId)
                      AND (:parkingLotId IS NULL OR pl.id = :parkingLotId)
                      AND (CAST(:fromDate AS timestamptz) IS NULL OR oa.created_at >= :fromDate)
                      AND (CAST(:toDate AS timestamptz) IS NULL OR oa.created_at <= :toDate)
                    """,
            nativeQuery = true
    )
    Page<SubscriptionRegistrantProjection> findSubscriptionRegistrants(
            @Param("searchTerm") String searchTerm,
            @Param("status") String status,
            @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );

    interface SubscriptionRegistrantProjection {
        Long getOwnerId();

        String getFullName();

        String getCustomerCode();

        String getAddress();

        String getPhone();

        String getZaloLink();

        String getStatus();

        long getRegisteredVehicleCount();

        long getCarCount();

        long getMotorbikeCount();

        long getExpiredSubscriptionCount();
    }
}
