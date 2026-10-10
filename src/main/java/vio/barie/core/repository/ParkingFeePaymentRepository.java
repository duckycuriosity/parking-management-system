package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;
import vio.barie.core.entity.ParkingFeePaymentEntity;
import java.util.List;
import java.util.Optional;

public interface ParkingFeePaymentRepository extends Repository<ParkingFeePaymentEntity, Long> {
    Optional<ParkingFeePaymentEntity> findById(Long id);
    <S extends ParkingFeePaymentEntity> S save(S entity);
    <S extends ParkingFeePaymentEntity> S saveAndFlush(S entity);
    void flush();
    Optional<ParkingFeePaymentEntity> findByRequestKey(String requestKey);
    Optional<ParkingFeePaymentEntity> findByPaymentId(Long paymentId);
    List<ParkingFeePaymentEntity> findByCalculationIdOrderByIdAsc(Long calculationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ParkingFeePaymentEntity p where p.id = :id")
    Optional<ParkingFeePaymentEntity> lockById(@Param("id") Long id);
}
