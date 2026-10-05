package vio.barie.core.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.LaneIncidentStatus;
import vio.barie.core.common.LaneIncidentType;
import vio.barie.core.entity.LaneIncidentEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LaneIncidentRepository extends JpaRepository<LaneIncidentEntity, Long> {

    Optional<LaneIncidentEntity> findFirstBySession_IdAndTypeAndStatus(Long sessionId, LaneIncidentType type,
                                                                      LaneIncidentStatus status);

    Optional<LaneIncidentEntity> findFirstByLaneIdAndTypeAndStatusInOrderByCreatedAtDesc(
            Long laneId, LaneIncidentType type, Collection<LaneIncidentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LaneIncidentEntity i where i.id = :id")
    Optional<LaneIncidentEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("select i from LaneIncidentEntity i " +
            "left join fetch i.session s " +
            "where i.status in (vio.barie.core.common.LaneIncidentStatus.PENDING, " +
            "vio.barie.core.common.LaneIncidentStatus.AWAITING_DECISION) " +
            "and (s is null or s.exitTime is null) " +
            "and (:laneId is null or i.laneId = :laneId) " +
            "order by i.createdAt desc")
    List<LaneIncidentEntity> findActiveIncidents(@Param("laneId") Long laneId);
}
