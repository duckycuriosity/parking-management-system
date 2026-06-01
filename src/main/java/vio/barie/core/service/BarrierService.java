package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.BarrierDetailResponse;
import vio.barie.core.dto.BarrierUpdateRequest;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BarrierService {
    private final BarrierRepository barrierRepository;
    private final BarrierCameraRepository barrierCameraRepository;
    private final CameraRepository cameraRepository;

    private final ParkingDeviceRepository parkingDeviceRepository;
    private final LaneRepository laneRepository;

    public List<BarrierDetailResponse> getAllBarrierDetails() {

        List<BarrierEntity> barriers = barrierRepository.findAllWithDeviceAndLane();

        List<String> barrierIds = barriers.stream()
                .map(BarrierEntity::getBarrierId)
                .collect(Collectors.toList());

        List<BarrierCameraEntity> barrierCameras = barrierCameraRepository.findByBarrierIdIn(barrierIds);

        List<Long> cameraIds = barrierCameras.stream()
                .map(BarrierCameraEntity::getCameraId)
                .distinct()
                .collect(Collectors.toList());


        Map<Long, CameraEntity> cameraMap = cameraRepository.findAllById(cameraIds).stream()
                .collect(Collectors.toMap(CameraEntity::getId, cam -> cam));


        Map<String, List<BarrierCameraEntity>> barrierCameraGroup = barrierCameras.stream()
                .collect(Collectors.groupingBy(BarrierCameraEntity::getBarrierId));

        return barriers.stream().map(barrier -> {
            BarrierDetailResponse.BarrierDetailResponseBuilder builder = BarrierDetailResponse.builder()
                    .barrierId(barrier.getBarrierId())
                    .description(barrier.getDescription());
            if (barrier.getDirection() != null) {
                builder.direction(barrier.getDirection());
            }

            // lấy Thiết bị điều khiển và làn
            if (barrier.getParkingDevice() != null) {
                builder.parkingDeviceId(barrier.getParkingDevice().getId());
                builder.deviceName(barrier.getParkingDevice().getDeviceId());

                if (barrier.getParkingDevice().getLane() != null) {
                    builder.laneId(barrier.getParkingDevice().getLane().getId());
                    builder.laneName(barrier.getParkingDevice().getLane().getName());
                }
            }

            List<BarrierCameraEntity> mappedCameras = barrierCameraGroup.get(barrier.getBarrierId());
            if (mappedCameras != null) {
                for (BarrierCameraEntity bc : mappedCameras) {
                    CameraEntity cam = cameraMap.get(bc.getCameraId());
                    if (cam != null) {
                        if ("FACE".equalsIgnoreCase(bc.getRole())) {
                            builder.faceCameraId(cam.getId());
                            builder.faceCameraName(cam.getName());
                        } else if ("PLATE".equalsIgnoreCase(bc.getRole())) {
                            builder.plateCameraId(cam.getId());
                            builder.plateCameraName(cam.getName());
                        }
                    }
                }
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
                .description(barrier.getDescription());

        if (barrier.getDirection() != null) {
            builder.direction(barrier.getDirection());
        }

        // map thông tin device và lane
        if (barrier.getParkingDevice() != null) {
            builder.parkingDeviceId(barrier.getParkingDevice().getId());
            builder.deviceName(barrier.getParkingDevice().getDeviceId());

            if (barrier.getParkingDevice().getLane() != null) {
                builder.laneId(barrier.getParkingDevice().getLane().getId());
                builder.laneName(barrier.getParkingDevice().getLane().getName());
            }
        }

        //  lấy thông tin cameras liên kết với barrier này
        List<BarrierCameraEntity> barrierCameras = barrierCameraRepository.findByBarrierId(barrierId);

        if (!barrierCameras.isEmpty()) {
            List<Long> cameraIds = barrierCameras.stream()
                    .map(BarrierCameraEntity::getCameraId)
                    .distinct()
                    .collect(Collectors.toList());


            Map<Long, CameraEntity> cameraMap = cameraRepository.findAllById(cameraIds).stream()
                    .collect(Collectors.toMap(CameraEntity::getId, cam -> cam));

            // map face camera và plate camera
            for (BarrierCameraEntity bc : barrierCameras) {
                CameraEntity cam = cameraMap.get(bc.getCameraId());
                if (cam != null) {
                    if ("FACE".equalsIgnoreCase(bc.getRole())) {
                        builder.faceCameraId(cam.getId());
                        builder.faceCameraName(cam.getName());
                    } else if ("PLATE".equalsIgnoreCase(bc.getRole())) {
                        builder.plateCameraId(cam.getId());
                        builder.plateCameraName(cam.getName());
                    }
                }
            }
        }

        return builder.build();
    }


    @Transactional
    public void updateBarrier(String barrierId, BarrierUpdateRequest request) {


        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId)
                .orElseThrow(() -> new BusinessException("Barrier id not found: " + barrierId, "BARRIER_NOT_FOUND", 404));

//        barrier.setDirection(request.getDirection());
        barrier.setDescription(request.getDescription());

        ParkingDeviceEntity device = parkingDeviceRepository.findById(request.getParkingDeviceId())
                .orElseThrow(() -> new BusinessException("Invalid control device", "DEVICE_NOT_FOUND", 400));

        LaneEntity lane = laneRepository.findById(request.getLaneId())
                .orElseThrow(() -> new BusinessException("Invalid lane", "LANE_NOT_FOUND", 400));


        device.setLane(lane);
        parkingDeviceRepository.save(device);

        barrier.setParkingDevice(device);
        barrierRepository.save(barrier);

        barrierCameraRepository.deleteByBarrierIdQuery(barrierId);

        List<BarrierCameraEntity> newMappings = new ArrayList<>();
        if (request.getFaceCameraId() != null) {
            BarrierCameraEntity faceCam = new BarrierCameraEntity();
            faceCam.setBarrierId(barrierId);
            faceCam.setCameraId(request.getFaceCameraId());
            faceCam.setRole("FACE");
            faceCam.setPriority(0); // Set default hoặc lấy từ request tùy bạn
            newMappings.add(faceCam);
        }

        if (request.getPlateCameraId() != null) {
            BarrierCameraEntity plateCam = new BarrierCameraEntity();
            plateCam.setBarrierId(barrierId);
            plateCam.setCameraId(request.getPlateCameraId());
            plateCam.setRole("PLATE");
            plateCam.setPriority(0);
            newMappings.add(plateCam);
        }

        if (!newMappings.isEmpty()) {
            barrierCameraRepository.saveAll(newMappings);
        }
    }

    @Transactional
    public void resetBarrierConfiguration(String barrierId) {

        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId)
                .orElseThrow(() -> new BusinessException("Barrier id not found: " + barrierId, "BARRIER_NOT_FOUND", 404));

        barrierCameraRepository.deleteByBarrierIdQuery(barrierId);

        barrier.setDirection(null);
        barrier.setDescription(null);
        barrierRepository.save(barrier);
    }

}
