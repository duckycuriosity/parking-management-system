package vio.barie.core.dto.parking;

public interface ParkingLotCapacityProjection {
    Long getParkingLotId();

    String getParkingLotName();

    Long getMotorbikeCapacity();

    Long getCarCapacity();

    Long getUsedTotal();

    Long getUsedMotorbike();

    Long getUsedCar();

    Long getUsedOther();
}
