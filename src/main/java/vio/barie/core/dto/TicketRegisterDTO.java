package vio.barie.core.dto;

import lombok.Data;

@Data
public class TicketRegisterDTO {
    private Long parkingCustomerId;
    private String deviceId;
    private String paymentMethod; // CASH | QR
}
