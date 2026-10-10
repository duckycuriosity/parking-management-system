package vio.barie.core.dto.fee;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Simulation has no calculationId; quoted/settled records retain their own historical snapshot. */
public record ParkingFeeResponse(Long calculationId, Long sessionId, String status,
        Instant calculatedAt, Instant calculationUntil, Instant validUntil,
        long subtotalVnd, long roundingAdjustmentVnd, long totalVnd,
        String inputFingerprint, JsonNode snapshot) { }
