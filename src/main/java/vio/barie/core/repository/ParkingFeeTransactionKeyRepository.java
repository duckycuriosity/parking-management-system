package vio.barie.core.repository;

import org.springframework.data.repository.Repository;
import vio.barie.core.entity.ParkingFeeTransactionKeyEntity;
import vio.barie.core.entity.ParkingFeeTransactionKeyId;
import java.util.Optional;

public interface ParkingFeeTransactionKeyRepository extends Repository<ParkingFeeTransactionKeyEntity, ParkingFeeTransactionKeyId> {
    Optional<ParkingFeeTransactionKeyEntity> findById(ParkingFeeTransactionKeyId id);
}
