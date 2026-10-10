package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;
import vio.barie.core.entity.FeeCalculationEntity;
import java.util.List;
import java.util.Optional;

public interface FeeCalculationRepository extends Repository<FeeCalculationEntity, Long> {
    Optional<FeeCalculationEntity> findById(Long id);
    <S extends FeeCalculationEntity> S save(S entity);
    <S extends FeeCalculationEntity> S saveAndFlush(S entity);
    void flush();
    List<FeeCalculationEntity> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId);
    List<FeeCalculationEntity> findBySessionIdAndStatusOrderByIdAsc(Long sessionId, String status);
    boolean existsBySessionIdAndStatus(Long sessionId, String status);
    boolean existsBySessionId(Long sessionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from FeeCalculationEntity c where c.id = :id")
    Optional<FeeCalculationEntity> lockById(@Param("id") Long id);
}
