package vio.barie.core.repository;

public interface DeviceLaneRepository {
    // Device info
    Long getDeviceId();

    String getDeviceCode();

    String getDeviceStatus();

    String getFirmwareVersion();

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
