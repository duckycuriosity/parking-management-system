package vio.barie.core.verification.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.VerificationMode;
import vio.barie.core.dto.LaneEventImage;
import vio.barie.core.dto.parking.PendingEventDetection;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.smoothing.SmoothingAIEvents;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.verification.ParkingSessionLifecycleService;
import vio.barie.core.verification.VehicleVerificationService;

import java.util.Optional;

/**
 * The original, still-default verification flow: a lane only opens once both a plate and a
 * matching face have arrived within the business window ({@link SmoothingAIEvents}), and on
 * exit the face must match the one that entered. Used for parking lots configured with
 * {@link VerificationMode#FACE_PLATE}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FacePlateVerificationService implements VehicleVerificationService {

    private final BarrierRepository barrierRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ParkingMessageService ioTService;
    private final SmoothingAIEvents smoothingAIEvents;
    private final ParkingSessionLifecycleService sessionLifecycleService;

    @Override
    public VerificationMode getMode() {
        return VerificationMode.FACE_PLATE;
    }

    @Override
    public void onFaceEvent(PendingEventDetection faceEvent) {
        smoothingAIEvents.ingestFace(faceEvent).ifPresent(this::emitMatch);
    }

    @Override
    public void onPlateEvent(PendingEventDetection plateEvent) {
        smoothingAIEvents.ingestPlate(plateEvent);
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
            VehicleEntity vehicle = null;
            if (detection.getPlateNumber() != null) {
                vehicle = vehicleRepository.findPlateByPlateNumber(detection.getPlateNumber());
            }

            ParkingCustomerEntity customer = null;
            if (detection.getProfileId() != null) {
                customer = parkingCustomerRepository.findByAiProfileId(detection.getProfileId()).orElse(null);
            }

            if (vehicle == null || customer == null) {
                log.warn("handleEntryEvent: missing vehicle or customer — plate={} profileId={}",
                        detection.getPlateNumber(), detection.getProfileId());
                ioTService.publishErrorWhenHandlingEvent("Không tìm thấy xe hoặc khách hàng tương ứng cho lượt vào", "", detection.getType(),
                        detection.getEntryBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "VEHICLE_OR_CUSTOMER_NOT_FOUND", null);
                return;
            }

            String barrierId = detection.getEntryBarrierId();
            Long laneId = detection.getEntryLaneId();
            if (barrierId == null || laneId == null) {
                log.warn("handleEntryEvent: missing barrier or lane info");
                ioTService.publishErrorWhenHandlingEvent("Thiếu thông tin barrier hoặc làn của lượt vào", "", detection.getType(),
                        detection.getEntryBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "LANE_INFO_MISSING", null);
                return;
            }

            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                log.warn("handleEntryEvent: barrier {} has no proper lane/device config", barrierId);
                ioTService.publishErrorWhenHandlingEvent("Cấu hình barrier/làn của lượt vào không hợp lệ", "", detection.getType(),
                        detection.getEntryBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "BARRIER_CONFIG_INVALID", null);
                return;
            }

            if (barrier.getParkingDevice().getLane().getParkingLot() == null) {
                log.warn("handleEntryEvent: lane {} has no parking lot", laneId);
                ioTService.publishErrorWhenHandlingEvent("Làn vào chưa thuộc bãi xe nào", "", detection.getType(),
                        detection.getEntryBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "LANE_NO_PARKING_LOT", null);
                return;
            }

            sessionLifecycleService.openEntry(vehicle, customer, barrier, detection);
        } catch (Exception ex) {
            log.error("handleEntryEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }

    @Transactional
    public void handleExitEvent(PendingEventDetection detection) {
        try {
            VehicleEntity vehicle = null;
            if (detection.getPlateNumber() != null) {
                vehicle = vehicleRepository.findPlateByPlateNumber(detection.getPlateNumber());
            }

            ParkingCustomerEntity customer = null;
            if (detection.getProfileId() != null) {
                customer = parkingCustomerRepository.findByAiProfileId(detection.getProfileId()).orElse(null);
            }

            if (vehicle == null || customer == null) {
                log.warn("handleExitEvent: missing vehicle or customer — plate={} profileId={}",
                        detection.getPlateNumber(), detection.getProfileId());
                ioTService.publishErrorWhenHandlingEvent("Không tìm thấy xe hoặc khách hàng tương ứng cho lượt ra", "", detection.getType(),
                        detection.getExitBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "VEHICLE_OR_CUSTOMER_NOT_FOUND", null);
                return;
            }

            String barrierId = detection.getExitBarrierId();
            Long laneId = detection.getExitLaneId();
            if (barrierId == null || laneId == null) {
                log.warn("handleExitEvent: missing barrier or lane info");
                ioTService.publishErrorWhenHandlingEvent("Thiếu thông tin barrier hoặc làn của lượt ra", "", detection.getType(),
                        detection.getExitBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "LANE_INFO_MISSING", null);
                return;
            }

            Optional<ParkingSessionEntity> openSessionOpt = sessionLifecycleService.findOpenSessionForVehicle(vehicle.getId());
            if (openSessionOpt.isEmpty()) {
                log.warn(
                        "handleExitEvent: no open parking session found for vehicle={} customer={} plate={} profileId={}",
                        vehicle.getId(), customer.getId(), vehicle.getPlateNumber(), customer.getAiProfileId());
                ioTService.publishErrorWhenHandlingEvent(
                        "Không tìm thấy phiên gửi xe đang mở khớp với xe tại làn ra",
                        "",
                        detection.getType(),
                        barrierId,
                        vehicle.getPlateNumber(),
                        customer.getAiProfileId(),
                        "SESSION_NOT_FOUND", null);
                return;
            }
            ParkingSessionEntity openSession = openSessionOpt.get();

            if (!openSession.getEntryCustomer().getAiProfileId().equals(detection.getProfileId())) {
                log.warn(
                        "handleExitEvent: Exit customer is not matching with entry customer for vehicle={} customer={} plate={} profileId={}",
                        vehicle.getId(), customer.getId(), vehicle.getPlateNumber(), customer.getAiProfileId());
                ioTService.publishErrorWhenHandlingEvent(
                        "Khuôn mặt người đi vào không khớp với khuôn mặt người đi ra",
                        "",
                        detection.getType(),
                        barrierId,
                        vehicle.getPlateNumber(),
                        customer.getAiProfileId(),
                        "FACE_MISMATCH",
                        LaneEventImage.from("exit_", detection.getListImageInfo()));
                return;
            }

            BarrierEntity barrier = barrierRepository.findByBarrierIdWithDeviceAndLaneAndParkingLot(barrierId)
                    .orElse(null);
            if (barrier == null || barrier.getParkingDevice() == null || barrier.getParkingDevice().getLane() == null) {
                ioTService.publishErrorWhenHandlingEvent("Cấu hình barrier/làn của lượt ra không hợp lệ", "", detection.getType(),
                        detection.getExitBarrierId(), detection.getPlateNumber(), detection.getProfileId(), "BARRIER_CONFIG_INVALID", null);
                return;
            }

            sessionLifecycleService.closeExit(openSession, customer, barrier, detection);
        } catch (Exception ex) {
            log.error("handleExitEvent: failed — type={}: {}", detection.getType(), ex.getMessage(), ex);
        }
    }
}
