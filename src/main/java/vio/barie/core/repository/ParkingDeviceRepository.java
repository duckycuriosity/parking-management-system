package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.entity.ParkingDeviceEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ParkingDeviceRepository extends JpaRepository<ParkingDeviceEntity, Long> {
    Optional<ParkingDeviceEntity> findByDeviceId(String deviceId);

    List<ParkingDeviceEntity> findByLane_Id(Long laneId);

    List<ParkingDeviceEntity> findByLaneIdIn(List<Long> laneIds);

    List<ParkingDeviceEntity> findByStatus(OperatingStatus status);

    List<ParkingDeviceEntity> findByLastSeenBeforeAndStatus(
            LocalDateTime time,
            OperatingStatus status);

    @Query(value = "SELECT name FROM lane WHERE id = :laneId", nativeQuery = true)
    Optional<String> findLaneNameById(@Param("laneId") Long laneId);

    @Query("SELECT p.deviceId FROM ParkingDeviceEntity p WHERE p.id IN :ids")
    List<String> findDeviceIdsByIds(@Param("ids") List<Long> ids);

    @Query(value = "SELECT " +
            "pd.id AS deviceId, pd.device_id AS deviceCode, pd.status AS deviceStatus, pd.firmware_version AS firmwareVersion, " +
            "pd.last_seen AS lastSeen, " +
            "pd.lane_id AS laneId, l.type AS typeLane, l.location AS location, " +
            "b.id AS barrierLocalId, b.barrier_id AS barrierId, b.description AS barrierDescription, b.direction AS direction, " +
            "c.id AS cameraId, c.name AS cameraName, c.url AS cameraUrl, c.type AS cameraType, bc.role AS cameraRole " +
            "FROM parking_devices pd " +
            "LEFT JOIN lane l ON l.id = pd.lane_id " +
            "LEFT JOIN barriers b ON b.parking_device_id = pd.id " +
            "LEFT JOIN barrier_camera bc ON bc.barrier_id = b.barrier_id " +
            "LEFT JOIN camera c ON c.id = bc.camera_id " +
            "WHERE pd.lane_id = :laneId", nativeQuery = true)
    List<DeviceLaneRepository> findAllDeviceDetailsByLaneId(@Param("laneId") Long laneId);

    @Query(value = "SELECT " +
            "pd.id AS deviceId, pd.device_id AS deviceCode, pd.status AS deviceStatus, pd.firmware_version AS firmwareVersion, " +
            "pd.last_seen AS lastSeen, " +
            "pd.lane_id AS laneId, l.type AS typeLane, l.location AS location, " +
            "b.id AS barrierLocalId, b.barrier_id AS barrierId, b.description AS barrierDescription, b.direction AS direction, " +
            "c.id AS cameraId, c.name AS cameraName, c.url AS cameraUrl, c.type AS cameraType, bc.role AS cameraRole " +
            "FROM parking_devices pd " +
            "LEFT JOIN lane l ON l.id = pd.lane_id " +
            "LEFT JOIN barriers b ON b.parking_device_id = pd.id " +
            "LEFT JOIN barrier_camera bc ON bc.barrier_id = b.barrier_id " +
            "LEFT JOIN camera c ON c.id = bc.camera_id ",
            nativeQuery = true)
    List<DeviceLaneRepository> findAllDeviceDetail();
}
