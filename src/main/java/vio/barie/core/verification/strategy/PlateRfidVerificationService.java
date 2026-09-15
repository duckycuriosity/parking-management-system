package vio.barie.core.verification.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vio.barie.core.common.VerificationMode;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.verification.VehicleVerificationService;

/**
 * Extension seam for {@link VerificationMode#PLATE_RFID}: no RFID reader/event source exists
 * yet, so there is nothing for a real RFID strategy to key off. Until that hardware integration
 * lands, a lot configured this way behaves like {@link VerificationMode#PLATE_ONLY} rather than
 * silently doing nothing. Replace the delegation below with real RFID-tag matching once the
 * ingestion pipeline for RFID reads exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlateRfidVerificationService implements VehicleVerificationService {

    private final PlateOnlyVerificationService plateOnlyVerificationService;

    @Override
    public VerificationMode getMode() {
        return VerificationMode.PLATE_RFID;
    }

    @Override
    public void onFaceEvent(PendingEventDetection faceEvent) {
        plateOnlyVerificationService.onFaceEvent(faceEvent);
    }

    @Override
    public void onPlateEvent(PendingEventDetection plateEvent) {
        log.warn("onPlateEvent: PLATE_RFID mode has no RFID hardware integration yet — falling back to plate-only verification");
        plateOnlyVerificationService.onPlateEvent(plateEvent);
    }
}
