package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.entity.CameraEntity;

import java.util.List;
import java.util.Optional;

public interface CameraRepository extends JpaRepository<CameraEntity, Long> {

    @Query("SELECT COUNT(c) FROM CameraEntity c " +
            "LEFT JOIN c.lane l LEFT JOIN l.parkingLot p " +
            "WHERE (:parkingLotId IS NULL or p.id =:parkingLotId) " +
            "AND (:buildingId IS NULL OR p.building.id = :buildingId)")
    long countCamerasWithFilters(@Param("buildingId") Long buildingId, @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT COUNT(c) FROM CameraEntity c " +
            "LEFT JOIN c.lane l LEFT JOIN l.parkingLot p " +
            "WHERE c.status = :status " +
            "AND (:parkingLotId IS NULL OR p.id = :parkingLotId) " +
            "AND (:buildingId IS NULL OR p.building.id = :buildingId)"
    )
    long countCamerasByStatusWithFilters(
            @Param("status") BaseStatus status,
            @Param("buildingId") Long buildingId,
            @Param("parkingLotId") Long parkingLotId);

    @Query("SELECT c FROM CameraEntity c LEFT JOIN FETCH c.lane l LEFT JOIN FETCH l.parkingLot")
    List<CameraEntity> findAllWithRelations();

    @Query("SELECT c FROM CameraEntity c " +
            "LEFT JOIN FETCH c.lane l " +
            "LEFT JOIN FETCH l.parkingLot p " +
            "WHERE (:status IS NULL OR c.status = :status) " +
            "AND (:searchKey IS NULL OR (c.name ILIKE :searchKey OR p.name ILIKE :searchKey))")
    List<CameraEntity> searchCameras(
            @Param("searchKey") String searchKey,
            @Param("status") BaseStatus status);

    @Query("SELECT c FROM CameraEntity c LEFT JOIN FETCH c.lane l LEFT JOIN FETCH l.parkingLot WHERE c.id = :id")
    Optional<CameraEntity> findByIdWithRelations(@Param("id") Long id);

    @Query("SELECT c FROM CameraEntity c JOIN FETCH c.lane WHERE c.id = :id")
    Optional<CameraEntity> findByIdWithLane(@Param("id") Long id);

    @Query("SELECT c FROM CameraEntity c JOIN FETCH c.lane WHERE c.id = :cameraCode")
    Optional<CameraEntity> findByCodeWithLane(@Param("cameraCode") String cameraCode);

    @Query("SELECT c FROM CameraEntity c WHERE c.aiCameraId = :aiCameraId")
    Optional<CameraEntity> findCameraByAiCameraId(@Param("aiCameraId") String aiCameraId);

    @Query("SELECT c FROM CameraEntity c WHERE c.uuid = :cameraUUID")
    Optional<CameraEntity> findCameraByUUID(@Param("cameraUUID") String cameraUUID);

    @Query("SELECT c FROM CameraEntity c LEFT JOIN FETCH c.lane l LEFT JOIN FETCH l.parkingLot " +
            "WHERE LOWER(l.parkingLot.name) LIKE LOWER(CONCAT('%', :searchKey, '%'))")
    List<CameraEntity> searchByParkingLotNameWithRelations(@Param("searchKey") String searchKey);
}