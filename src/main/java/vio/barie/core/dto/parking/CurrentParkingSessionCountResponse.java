package vio.barie.core.dto.parking;

public record CurrentParkingSessionCountResponse(
        Long currentEntries,
        Long currentExits,
        Long currentParking
) {}

