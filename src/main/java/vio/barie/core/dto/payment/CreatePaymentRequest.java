package vio.barie.core.dto.payment;

import lombok.Data;
import vio.barie.core.entity.ParkingSessionEntity;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    private BigDecimal amount;
    private String goodsName;
    private String description;
    private String payType;  // override config nếu cần
}
