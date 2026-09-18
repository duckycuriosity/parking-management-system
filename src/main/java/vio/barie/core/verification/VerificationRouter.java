package vio.barie.core.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vio.barie.core.common.VerificationMode;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.ParkingLotEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entry point for the "core checks the parking lot's mode before dispatching" flow: given
 * the parking lot a face/plate detection belongs to, resolves the {@link VerificationMode}
 * configured for that lot and forwards the event to the matching {@link VehicleVerificationService}.
 */
@Slf4j
@Component
public class VerificationRouter {

    private final Map<VerificationMode, VehicleVerificationService> strategies;

    public VerificationRouter(List<VehicleVerificationService> services) {
        this.strategies = services.stream()
                .collect(Collectors.toMap(VehicleVerificationService::getMode, Function.identity()));
    }

    public void routeFaceEvent(PendingEventDetection faceEvent, ParkingLotEntity parkingLot) {
        resolve(parkingLot).onFaceEvent(faceEvent);
    }

    public void routePlateEvent(PendingEventDetection plateEvent, ParkingLotEntity parkingLot) {
        resolve(parkingLot).onPlateEvent(plateEvent);
    }

    private VehicleVerificationService resolve(ParkingLotEntity parkingLot) {
        VerificationMode mode = parkingLot != null && parkingLot.getVerificationMode() != null
                ? parkingLot.getVerificationMode()
                : VerificationMode.FACE_PLATE;
        VehicleVerificationService strategy = strategies.get(mode);
        if (strategy == null) {
            throw new ServiceException("No verification strategy registered for mode " + mode);
        }
        return strategy;
    }
}
