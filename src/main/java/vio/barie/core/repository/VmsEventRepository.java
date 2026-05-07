package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.VmsEventEntity;

public interface VmsEventRepository extends JpaRepository<VmsEventEntity, Long> {
    boolean existsByVmsEventId(Long vmsEventId);
}