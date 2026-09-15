package vio.barie.core.verification;

import vio.barie.core.common.VerificationMode;
import vio.barie.core.dto.parking.PendingEventDetection;

/**
 * One implementation per {@link VerificationMode}. {@link VerificationRouter} picks the
 * implementation matching a parking lot's configured mode and forwards raw face/plate
 * detections to it — each implementation decides what evidence is required before a
 * session/barrier action happens.
 */
public interface VehicleVerificationService {

    VerificationMode getMode();

    void onFaceEvent(PendingEventDetection faceEvent);

    void onPlateEvent(PendingEventDetection plateEvent);
}
