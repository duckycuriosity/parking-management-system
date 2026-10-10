package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vio.barie.core.entity.OwnerAccountEntity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;

public interface OwnerAccountRepository extends JpaRepository<OwnerAccountEntity, Long>, JpaSpecificationExecutor<OwnerAccountEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OwnerAccountEntity o where o.id = :id")
    Optional<OwnerAccountEntity> findForUpdate(@Param("id") Long id);

    @Query("select v.ownerId as ownerId, count(v) as vehicleCount from VehicleEntity v where v.ownerId in :ownerIds group by v.ownerId")
    List<OwnerVehicleCount> vehicleCounts(@Param("ownerIds") List<Long> ownerIds);

    interface OwnerVehicleCount {
        Long getOwnerId();
        long getVehicleCount();
    }

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
                        oa.image_url AS "imageUrl",
                        oa.phone AS "phone",
                        oa.zalo_link AS "zaloLink",
                        oa.note AS "note",
                        oa.status AS "status",
                        oa.created_at AS "createdAt",
                        oa.updated_at AS "updatedAt",
                        (
                            SELECT COUNT(*)
                            FROM vehicle v
                            WHERE v.owner_id = oa.id
                        ) AS "registeredVehicleCount"
                    FROM owner_account oa
                    WHERE (:searchTerm IS NULL
                        OR LOWER(oa.full_name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(oa.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.identity_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.address, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
                      AND (:status IS NULL OR oa.status = :status)
                      AND (CAST(:fromDate AS timestamptz) IS NULL OR oa.created_at >= :fromDate)
                      AND (CAST(:toDate AS timestamptz) IS NULL OR oa.created_at <= :toDate)
                    ORDER BY oa.created_at DESC, oa.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(oa.id)
                    FROM owner_account oa
                    WHERE (:searchTerm IS NULL
                        OR LOWER(oa.full_name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(oa.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.identity_number, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.address, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
                      AND (:status IS NULL OR oa.status = :status)
                      AND (CAST(:fromDate AS timestamptz) IS NULL OR oa.created_at >= :fromDate)
                      AND (CAST(:toDate AS timestamptz) IS NULL OR oa.created_at <= :toDate)
                    """,
            nativeQuery = true
    )
    Page<SubscriptionRegistrantProjection> findSubscriptionRegistrants(
            @Param("searchTerm") String searchTerm,
            @Param("status") String status,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );

    interface SubscriptionRegistrantProjection {
        Long getOwnerId();

        String getFullName();

        String getCustomerCode();

        String getAddress();

        String getImageUrl();

        String getPhone();

        String getZaloLink();

        String getNote();

        String getStatus();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        long getRegisteredVehicleCount();
    }
}
