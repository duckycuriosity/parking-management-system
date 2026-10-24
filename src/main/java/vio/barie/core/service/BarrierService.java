package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.BarrierDetailResponse;
import vio.barie.core.dto.BarrierUpdateRequest;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.mqtt.ParkingMessageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BarrierService {
    private final BarrierRepository barrierRepository;
    private final BarrierCameraRepository barrierCameraRepository;
    private final CameraRepository cameraRepository;

    private final ParkingDeviceRepository parkingDeviceRepository;
    private final LaneRepository laneRepository;
    private final ParkingMessageService parkingMessageService;

    @Transactional
    public List<BarrierDetailResponse> getAllBarrierDetails() {

        List<BarrierEntity> barriers = barrierRepository.findAllWithDeviceAndLane();

        return barriers.stream().map(barrier -> {
            BarrierDetailResponse.BarrierDetailResponseBuilder builder = BarrierDetailResponse.builder()
                    .barrierId(barrier.getBarrierId())
                    .description(barrier.getDescription())
                    .ipBarrier(barrier.getIpBarrier());
            if (barrier.getDirection() != null) {
                builder.direction(barrier.getDirection());
            }

            // lấy Thiết bị điều khiển và làn
            if (barrier.getParkingDevice() != null) {
                builder.parkingDeviceId(barrier.getParkingDevice().getId());
                builder.deviceName(barrier.getParkingDevice().getDeviceId());
            }
            if (barrier.getLane() != null) {
                builder.laneId(barrier.getLane().getId());
                builder.laneName(barrier.getLane().getName());
            }

            return builder.build();
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BarrierDetailResponse getBarrierDetailById(String barrierId) {

        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId)
                .orElseThrow(() -> new BusinessException("Barrier id not found: " + barrierId, "BARRIER_NOT_FOUND", 404));

        // khởi tạo builder cho response
        BarrierDetailResponse.BarrierDetailResponseBuilder builder = BarrierDetailResponse.builder()
                .barrierId(barrier.getBarrierId())
                .description(barrier.getDescription())
                .ipBarrier(barrier.getIpBarrier());
        if (barrier.getDirection() != null) {
            builder.direction(barrier.getDirection());
        }

        // map thông tin device và lane
        if (barrier.getParkingDevice() != null) {
            builder.parkingDeviceId(barrier.getParkingDevice().getId());
            builder.deviceName(barrier.getParkingDevice().getDeviceId());
        }
        if (barrier.getLane() != null) {
            builder.laneId(barrier.getLane().getId());
            builder.laneName(barrier.getLane().getName());
        }


        return builder.build();
    }


    @Transactional
    public BarrierDetailResponse updateBarrier(String barrierId, BarrierUpdateRequest request) {

        // kiểm tra sự tồn tại của Barrier cứng
        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId)
                .orElseThrow(() -> new BusinessException("Barrier id not found: " + barrierId, "BARRIER_NOT_FOUND", 404));

        barrier.setDirection(request.getDirection());
        barrier.setDescription(request.getDescription());
        barrier.setIpBarrier(request.getIpBarrier());

        barrierRepository.save(barrier);
        BarrierDetailResponse response = getBarrierDetailById(barrierId);
        return response;
    }

    @Transactional
    public void manualOpenBarrier(String barrierId, String reasonOpenCmd) {
        BarrierEntity barrier = barrierRepository
                .findByBarrierId(barrierId)
                .orElseThrow(() ->
                        new RuntimeException("Không tìm thấy barrier: " + barrierId)
                );
        barrier.setReasonOpenCmd(reasonOpenCmd.trim());
        barrierRepository.save(barrier);
        parkingMessageService.publishManualOpenCommand(barrierId);
    }

}
