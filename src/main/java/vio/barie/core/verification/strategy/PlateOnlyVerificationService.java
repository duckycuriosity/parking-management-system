package vio.barie.core.verification.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.VerificationMode;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.smoothing.SmoothingAIEvents;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.verification.ParkingSessionLifecycleService;
import vio.barie.core.verification.VehicleVerificationService;

import java.util.Optional;

/**
 * Guest/walk-in flow for {@link VerificationMode#PLATE_ONLY} lots: only the plate is required
 * to open/close the barrier — no face pairing, no face-match check on exit. Face events are
 * still delivered to this strategy (a lane keeps its face camera) but are ignored for gating;
 * long-term face tracking for security purposes is a separate, not-yet-built capability.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlateOnlyVerificationService implements VehicleVerificationService {

    private final VehicleRepository vehicleRepository;
    private final BarrierRepository barrierRepository;
    private final ParkingMessageService ioTService;
    private final SmoothingAIEvents smoothingAIEvents;
    private final ParkingSessionLifecycleService sessionLifecycleService;

    @Override
    public VerificationMode getMode() {
        return VerificationMode.PLATE_ONLY;
    }

    @Override
    public void onFaceEvent(PendingEventDetection faceEvent) {
        log.debug("onFaceEvent: ignored — lane is configured for PLATE_ONLY verification");
    }

    @Override
    public void onPlateEvent(PendingEventDetection plateEvent) {
        smoothingAIEvents.ingestPlateOnly(plateEvent).ifPresent(this::emitMatch);
    }

    private void emitMatch(PendingEventDetection detection) {
        if (detection.getDirectionType() == DirectionType.ENTRY) {
            handleEntryEvent(detection);
            return;
        }

        if (detection.getDirectionType() == DirectionType.EXIT) {
            handleExitEvent(detection);
            return;
        }

        log.warn("emitMatch: unknown direction type={}", detection.getDirectionType());
    }

    @Transactional
    public void handleEntryEvent(PendingEventDetection detection) {
        try {
            VehicleEntity vehicle = detection.getPlateNumber() != null
                    ? vehicleRepository.findPlateByPlateNumber(detection.getPlateNumber())
                    : null;
            if (vehicle == null) {
                log.warn("handleEntryEvent: vehicle not found — plate={}", detection.getPlateNumber());
                ioTService.publishErrorWhenHandlingEvent("Vehicle not found for entry event", "", detection.getType());
                return;
            }

            String barrierId = detection.getEntryBarrierId();
            if (barrierId == null) {
                log.warn("handleEntryEvent: missing barrier info");
                ioTService.publishErrorWhenHandlingEvent("Missing barrier info for entry event", "",
                        detection.getType());
                return;
            }

            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                log.warn("handleEntryEvent: barrier {} has no proper lane/device config", barrierId);
                ioTService.publishErrorWhenHandlingEvent("Invalid barrier configuration for entry event", "",
                        detection.getType());
                return;
            }

            sessionLifecycleService.openEntry(vehicle, null, barrier, detection);
        } catch (Exception ex) {
            log.error("handleEntryEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }

    @Transactional
    public void handleExitEvent(PendingEventDetection detection) {
        try {
            VehicleEntity vehicle = detection.getPlateNumber() != null
                    ? vehicleRepository.findPlateByPlateNumber(detection.getPlateNumber())
                    : null;
            if (vehicle == null) {
                log.warn("handleExitEvent: vehicle not found — plate={}", detection.getPlateNumber());
                ioTService.publishErrorWhenHandlingEvent("Vehicle not found for exit event", "", detection.getType());
                return;
            }

            String barrierId = detection.getExitBarrierId();
            if (barrierId == null) {
                log.warn("handleExitEvent: missing barrier info");
                ioTService.publishErrorWhenHandlingEvent("Missing barrier info for exit event", "",
                        detection.getType());
                return;
            }

            Optional<ParkingSessionEntity> openSessionOpt = sessionLifecycleService.findOpenSessionForVehicle(vehicle.getId());
            if (openSessionOpt.isEmpty()) {
                log.warn("handleExitEvent: no open parking session found for vehicle={} plate={}",
                        vehicle.getId(), vehicle.getPlateNumber());
                ioTService.publishErrorWhenHandlingEvent(
                        "Khong tim thay phien gui xe (parking_session) dang mo khop voi bien so tai lan ra",
                        "",
                        detection.getType(),
                        barrierId,
                        vehicle.getPlateNumber(),
                        null);
                return;
            }

            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                ioTService.publishErrorWhenHandlingEvent("Invalid barrier configuration for exit event", "",
                        detection.getType());
                return;
            }

            sessionLifecycleService.closeExit(openSessionOpt.get(), null, barrier, detection);
        } catch (Exception ex) {
            log.error("handleExitEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }
}
