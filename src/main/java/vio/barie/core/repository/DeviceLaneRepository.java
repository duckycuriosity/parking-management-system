package vio.barie.core.repository;

import java.time.LocalDateTime;

public interface DeviceLaneRepository {
    // Device info
    Long getDeviceId();

    String getDeviceCode();

    String getDeviceStatus();

    String getFirmwareVersion();

    LocalDateTime getLastSeen();

    Long getLaneId();

    String getTypeLane();

    String getLocation();

    // Barrier info
    Long getBarrierLocalId();

    String getBarrierId();

    String getBarrierDescription();

    String getDirection();

    String getIpBarrier();

    // Camera info
    Long getCameraId();

    String getCameraName();

    String getCameraUrl();

    String getCameraType();

    String getCameraRole();

    String getUuid();

}
