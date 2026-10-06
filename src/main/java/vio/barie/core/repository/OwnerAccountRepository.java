package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.entity.OwnerAccountEntity;

public interface OwnerAccountRepository extends JpaRepository<OwnerAccountEntity, Long>, JpaSpecificationExecutor<OwnerAccountEntity> {

    @Query(value = "SELECT COUNT(1) FROM vehicle v WHERE v.owner_id = :ownerId", nativeQuery = true)
    long countVehiclesByOwnerId(@Param("ownerId") Long ownerId);
}
