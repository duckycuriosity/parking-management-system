package vio.barie.core.dto.payment;

import lombok.Data;
import vio.barie.core.entity.ParkingSessionEntity;

@Data
public class CreatePaymentRequest {
    private ParkingSessionEntity session;
    private int amount;
    private String goodsName;
    private String description;
}
