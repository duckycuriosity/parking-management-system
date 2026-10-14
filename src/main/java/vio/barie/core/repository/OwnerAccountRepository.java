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
                        oa.id AS ownerId,
                        oa.fullName AS fullName,
                        oa.identityNumber AS customerCode,
                        oa.address AS address,
                        oa.imageUrl AS imageUrl,
                        oa.phone AS phone,
                        oa.zaloLink AS zaloLink,
                        oa.note AS note,
                        oa.status AS status,
                        oa.createdAt AS createdAt,
                        oa.updatedAt AS updatedAt,
                        (
                            SELECT COUNT(v.id)
                            FROM VehicleEntity v
                            WHERE v.ownerId = oa.id
                        ) AS registeredVehicleCount
                    FROM OwnerAccountEntity oa
                    WHERE (:searchTerm IS NULL
                        OR LOWER(oa.fullName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(oa.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.identityNumber, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.address, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
                      AND (:status IS NULL OR oa.status = :status)
                      AND (:fromDate IS NULL OR oa.createdAt >= :fromDate)
                      AND (:toDate IS NULL OR oa.createdAt <= :toDate)
                    ORDER BY oa.createdAt DESC, oa.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(oa.id)
                    FROM OwnerAccountEntity oa
                    WHERE (:searchTerm IS NULL
                        OR LOWER(oa.fullName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(oa.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.identityNumber, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
                        OR LOWER(COALESCE(oa.address, '')) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
                      AND (:status IS NULL OR oa.status = :status)
                      AND (:fromDate IS NULL OR oa.createdAt >= :fromDate)
                      AND (:toDate IS NULL OR oa.createdAt <= :toDate)
                    """
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

        OffsetDateTime getCreatedAt();

        OffsetDateTime getUpdatedAt();

        long getRegisteredVehicleCount();
    }
}
