package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.LaneIncidentStatus;
import vio.barie.core.common.LaneIncidentType;
import vio.barie.core.entity.LaneIncidentEntity;

import java.util.List;
import java.util.Optional;

public interface LaneIncidentRepository extends JpaRepository<LaneIncidentEntity, Long> {

    Optional<LaneIncidentEntity> findFirstBySession_IdAndTypeAndStatus(Long sessionId, LaneIncidentType type,
                                                                      LaneIncidentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LaneIncidentEntity i where i.id = :id")
    Optional<LaneIncidentEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("select i from LaneIncidentEntity i " +
            "join fetch i.session s " +
            "where i.status = vio.barie.core.common.LaneIncidentStatus.PENDING " +
            "and s.exitTime is null " +
            "and (:laneId is null or i.laneId = :laneId) " +
            "order by i.createdAt desc")
    List<LaneIncidentEntity> findPendingWithOpenSession(@Param("laneId") Long laneId);
}
