package vio.barie.core.dto.payment;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import vio.barie.core.entity.ParkingSessionEntity;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    /** Internal invoice allocated and committed before contacting the gateway. */
    @JsonIgnore
    private String invoiceNo;
    private BigDecimal amount;
    private String goodsName;
    private String description;
    private String payType;  // override config nếu cần
}
