package vio.barie.core.repository;

import java.time.LocalDateTime;

public interface DeviceLaneRepository {
    // Device info
    Long getDeviceId();

    String getDeviceCode();

    String getDeviceStatus();

    String getFirmwareVersion();

    LocalDateTime getLastSeen();

    String getLocation();

    // Barrier info
    Long getBarrierLocalId();

    String getBarrierId();

    String getBarrierDescription();

    String getDirection();

    // Camera info
    Long getCameraId();

    String getCameraName();

    String getCameraUrl();

    String getCameraType();

    String getCameraRole();

}
