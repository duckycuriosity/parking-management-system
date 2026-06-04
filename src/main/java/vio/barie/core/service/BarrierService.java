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
    public BarrierDetailResponse updateBarrier(String barrierId, BarrierUpdateRequest request) {


        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId)
                .orElseThrow(() -> new BusinessException("Barrier id not found: " + barrierId, "BARRIER_NOT_FOUND", 404));

        barrier.setDirection(request.getDirection());
        barrier.setDescription(request.getDescription());

        ParkingDeviceEntity device = parkingDeviceRepository.findByDeviceId(request.getDeviceName())
                .orElseThrow(() -> new BusinessException("Invalid control device", "DEVICE_NOT_FOUND", 400));

        LaneEntity lane = laneRepository.findById(request.getLaneId())
                .orElseThrow(() -> new BusinessException("Invalid lane", "LANE_NOT_FOUND", 400));


        device.setLane(lane);
        parkingDeviceRepository.save(device);

        barrier.setParkingDevice(device);
        barrierRepository.save(barrier);

        barrierCameraRepository.deleteByBarrierIdQuery(barrierId);

        List<BarrierCameraEntity> existingMappings = barrierCameraRepository.findByBarrierId(barrierId);
        BarrierCameraEntity faceCam = existingMappings.stream()
                .filter(m -> "FACE".equalsIgnoreCase(m.getRole()))
                .findFirst()
                .orElseGet(() -> {
                    BarrierCameraEntity newMapping = new BarrierCameraEntity();
                    newMapping.setBarrierId(barrierId);
                    newMapping.setRole("FACE");
                    newMapping.setPriority(0);
                    return newMapping;
                });

        // Cập nhật ID (nếu request truyền null, nó sẽ update null -> ngắt liên kết)
        faceCam.setCameraId(request.getFaceCameraId());
        faceCam.setDescription(request.getDescription());
        barrierCameraRepository.save(faceCam);

        BarrierCameraEntity plateCam = existingMappings.stream()
                .filter(m -> "PLATE".equalsIgnoreCase(m.getRole()))
                .findFirst()
                .orElseGet(() -> {
                    BarrierCameraEntity newMapping = new BarrierCameraEntity();
                    newMapping.setBarrierId(barrierId);
                    newMapping.setRole("PLATE");
                    newMapping.setPriority(0);
                    return newMapping;
                });

        // Cập nhật ID (nếu request truyền null, nó sẽ update null -> ngắt liên kết)
        plateCam.setCameraId(request.getPlateCameraId());
        plateCam.setDescription(request.getDescription());
        barrierCameraRepository.save(plateCam);


        List<Long> cameraIdsToUpdateLane = new ArrayList<>();
        if (request.getFaceCameraId() != null) {
            cameraIdsToUpdateLane.add(request.getFaceCameraId());
        }
        if (request.getPlateCameraId() != null) {
            cameraIdsToUpdateLane.add(request.getPlateCameraId());
        }

        if (!cameraIdsToUpdateLane.isEmpty()) {
            List<CameraEntity> cameras = cameraRepository.findAllById(cameraIdsToUpdateLane);
            for (CameraEntity cam : cameras) {
                cam.setLane(lane);
            }
            cameraRepository.saveAll(cameras);
        }
        return getBarrierDetailById(barrierId);
    }

    @Transactional
    public void resetBarrierConfiguration(String barrierId) {

        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId)
                .orElseThrow(() -> new BusinessException("Barrier id not found: " + barrierId, "BARRIER_NOT_FOUND", 404));

        List<BarrierCameraEntity> mappings = barrierCameraRepository.findByBarrierId(barrierId);
        for (BarrierCameraEntity mapping : mappings) {
            mapping.setCameraId(null);
            mapping.setDescription(null);
        }
        barrierCameraRepository.saveAll(mappings);
//        if (barrier.getParkingDevice() != null) {
//            ParkingDeviceEntity device = barrier.getParkingDevice();
//            device.setLane(null);
//            parkingDeviceRepository.save(device);
//        }

        barrier.setDirection(null);
        barrier.setDescription(null);
        barrierRepository.save(barrier);
    }

}
