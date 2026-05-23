package vio.barie.core.dto.parking;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class PaymentConfirmedCommand {

    @JsonProperty("type")
    @Builder.Default
    private String type = "qr_payment_confirmed";

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("barrier_id")
    private String barrierId;

    @JsonProperty("amount_paid")
    private BigDecimal amountPaid;

    @JsonProperty("payment_app")
    @Builder.Default
    private String paymentApp = "App Banking";

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("timestamp")
    private String timestamp;
}
