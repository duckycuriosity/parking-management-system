package vio.barie.core.dto.fee;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Read-only operational evidence, including real extra transfers requiring reconciliation. */
public record ParkingFeePaymentEvidenceResponse(Long attemptId, Long calculationId, Long paymentId,
        long requestedAmountVnd, String currency, String status, String applicationStatus,
        String applicationReason, Instant requestedAt, Instant confirmedAt, JsonNode observations) { }
