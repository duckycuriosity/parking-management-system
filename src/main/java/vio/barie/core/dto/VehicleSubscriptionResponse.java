package vio.barie.core.dto;

import java.time.LocalDateTime;

public interface VehicleSubscriptionResponse {
    Long getSubscriptionId();
    String getPlateNumber();
    String getSubscriberName();
    String getParkingLotName();
    LocalDateTime getExpirationDate();
    String getTicketType();
    String getStatus();
}
