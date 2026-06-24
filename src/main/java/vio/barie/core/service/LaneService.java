package vio.barie.core.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.*;
import vio.barie.core.entity.BarrierCameraEntity;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.mapper.LaneMapper;
import vio.barie.core.repository.BarrierCameraRepository;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.LaneRepository;
import vio.barie.core.repository.ParkingLotRepository;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.utils.DirectionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LaneService {

    private final LaneRepository laneRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final LaneMapper laneMapper;
    private final CameraRepository cameraRepository;
    private final ParkingDeviceRepository parkingDeviceRepository;
    private final BarrierRepository barrierRepository;
    private final BarrierCameraRepository barrierCameraRepository;

    @Transactional
    public LaneResponse create(LaneCreateRequest request) {
        ParkingLotEntity parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));

        LaneEntity entity = laneMapper.toEntity(request);
        entity.setParkingLot(parkingLot);

        LaneEntity saved = laneRepository.save(entity);

        // associate cameras
        if (request.getCameraIds() != null && !request.getCameraIds().isEmpty()) {
            Iterable<CameraEntity> cams = cameraRepository.findAllById(request.getCameraIds());
            for (CameraEntity cam : cams) {
                cam.setLane(saved);
            }
            cameraRepository.saveAll(cams);
        }

        // associate parking devices
        if (request.getParkingDeviceIds() != null && !request.getParkingDeviceIds().isEmpty()) {
            if (request.getParkingDeviceIds().size() > 1) {
                throw new BusinessException("Mỗi làn chỉ được phép gắn tối đa 1 thiết bị!", "INVALID_DEVICE_COUNT", 400);
            }
            Long targetDeviceId = request.getParkingDeviceIds().get(0);
            if (targetDeviceId != null) {
                ParkingDeviceEntity d = parkingDeviceRepository.findById(targetDeviceId)
                        .orElseThrow(() -> new BusinessException("Parking device not found", "DEVICE_NOT_FOUND", 404));

                // Validate
                if (d.getLane() != null) {
                    String errorMsg = String.format("Thiết bị [%s] hiện tại đang được gắn ở lane: %s", d.getDeviceId(), d.getLane().getName());
                    throw new BusinessException(errorMsg, "DEVICE_ALREADY_ASSIGNED_TO_LANE", 400);
                }

                d.setLane(saved);
                parkingDeviceRepository.save(d);
            }
        }


        return laneMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<LaneResponse> findAll(Pageable pageable) {
        Page<LaneEntity> lanePage = laneRepository.findAllWithParkingLot(pageable);
        List<LaneResponse> responses = laneMapper.toResponseList(lanePage.getContent());

        List<Long> laneIds = responses.stream()
                .map(LaneResponse::getId)
                .collect(Collectors.toList());
        if (!laneIds.isEmpty()) {

            List<ParkingDeviceEntity> devices = parkingDeviceRepository.findByLaneIdIn(laneIds);

            Map<Long, ParkingDeviceEntity> deviceByLaneMap = devices.stream()
                    .collect(Collectors.toMap(
                            d -> d.getLane().getId(), // Key là laneId
                            d -> d,
                            (existing, replacement) -> existing

                    ));


            for (LaneResponse res : responses) {
                ParkingDeviceEntity device = deviceByLaneMap.get(res.getId());
                if (device != null) {
                    res.setParkingDeviceIds(device.getId());
                    res.setParkingDeviceCode(device.getDeviceId());
                }
            }
        }
        return new PageImpl<>(responses, pageable, lanePage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public LaneResponse findById(Long id) {
        LaneEntity entity = laneRepository.findByIdWithParkingLot(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        LaneResponse response = laneMapper.toResponse(entity);
        if (response.getParkingDeviceIds() != null) {
            parkingDeviceRepository.findById(response.getParkingDeviceIds())
                    .ifPresent(d -> response.setParkingDeviceCode(d.getDeviceId()));
        }
        return response;
    }

    public List<LaneResponse> findLanesByParkingLotId(Long parkingLotId, DirectionType type, BaseStatus status) {
        List<LaneEntity> entities;
        if (type != null) {
            if (status != null) {
                entities = laneRepository.findAllByParkingLotIdAndTypeAndStatus(parkingLotId, type, status);
            } else {
                entities = laneRepository.findAllByParkingLotIdAndTypeAndStatus(parkingLotId, type, null);
            }
        } else {
            entities = laneRepository.findAllByParkingLotIdAndTypeAndStatus(parkingLotId, type, status);

        }
        return laneMapper.toResponseList(entities);
    }


    @Transactional
    public LaneResponse update(Long id, LaneCreateRequest request) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));

        ParkingLotEntity parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));

        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        entity.setParkingLot(parkingLot);

        LaneEntity saved = laneRepository.save(entity);

        // update camera associations (simple strategy: set lane for listed cameras)
        List<CameraEntity> currentCameras = entity.getCameras() != null
                ? new ArrayList<>(entity.getCameras())
                : new ArrayList<>();
        List<Long> newCameraIds = request.getCameraIds() != null ? request.getCameraIds() : List.of();
        for (CameraEntity oldCam : currentCameras) {
            if (!newCameraIds.contains(oldCam.getId())) {
                oldCam.setLane(null);
            }
        }
        if (!newCameraIds.isEmpty()) {
            Iterable<CameraEntity> cams = cameraRepository.findAllById(newCameraIds);
            for (CameraEntity cam : cams) {
                cam.setLane(saved);
            }
            cameraRepository.saveAll(cams);
        }

        List<ParkingDeviceEntity> currentDevices = parkingDeviceRepository.findByLane_Id(id);
        if (request.getParkingDeviceIds() == null || request.getParkingDeviceIds().isEmpty()) {
            for (ParkingDeviceEntity oldDevice : currentDevices) {
                oldDevice.setLane(null);
            }
            parkingDeviceRepository.saveAll(currentDevices);
        } else {
            if (request.getParkingDeviceIds().size() > 1) {
                throw new BusinessException("Mỗi làn chỉ được phép gắn tối đa 1 thiết bị!", "INVALID_DEVICE_COUNT", 400);
            }
            Long targetDeviceId = request.getParkingDeviceIds().get(0);
            if (targetDeviceId != null) {
                ParkingDeviceEntity requestedDevice = parkingDeviceRepository.findById(targetDeviceId)
                        .orElseThrow(() -> new BusinessException("Parking device not found", "DEVICE_NOT_FOUND", 404));

                if (requestedDevice.getLane() != null && !requestedDevice.getLane().getId().equals(id)) {
                    String errorMsg = String.format("Thiết bị [%s] hiện tại đang được gắn ở lane: %s",
                            requestedDevice.getDeviceId(), requestedDevice.getLane().getName());
                    throw new BusinessException(errorMsg, "DEVICE_ALREADY_ASSIGNED_TO_LANE", 400);
                }

                for (ParkingDeviceEntity oldDevice : currentDevices) {
                    if (!oldDevice.getId().equals(requestedDevice.getId())) {
                        oldDevice.setLane(null);
                        parkingDeviceRepository.save(oldDevice);
                    }
                }
                requestedDevice.setLane(saved);
                parkingDeviceRepository.save(requestedDevice);
            }

        }

        return laneMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));

        List<ParkingDeviceEntity> linkedDevices = parkingDeviceRepository.findByLane_Id(id);
        if (!linkedDevices.isEmpty()) {
            throw new BusinessException(
                    "Làn này đang được gắn thiết bị, vui lòng gỡ thiết bị trước khi xóa!",
                    "LANE_HAS_DEVICE", 400);
        }
        try {
            laneRepository.delete(entity);
            laneRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    "Làn này đang được liên kết với dữ liệu khác, không thể xóa!",
                    "LANE_IN_USE", 409);
        }
    }

    @Transactional
    public void assignCameras(Long laneId, java.util.List<Long> cameraIds) {
        LaneEntity lane = laneRepository.findById(laneId)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        Iterable<CameraEntity> cams = cameraRepository.findAllById(cameraIds);
        for (CameraEntity cam : cams) {
            cam.setLane(lane);
        }
        cameraRepository.saveAll(cams);
    }

    @Transactional
    public void assignParkingDevices(Long laneId, java.util.List<Long> deviceIds) {
        LaneEntity lane = laneRepository.findById(laneId)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        Iterable<ParkingDeviceEntity> devs = parkingDeviceRepository.findAllById(deviceIds);
        for (ParkingDeviceEntity d : devs) {
            d.setLane(lane);
        }
        parkingDeviceRepository.saveAll(devs);
    }

    @Transactional
    public void assignBarrierCameras(Long laneId, List<BarrierCameraAssignmentRequest> requests) {
        LaneEntity lane = laneRepository.findById(laneId)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));

        List<ParkingDeviceEntity> devices = parkingDeviceRepository.findByLane_Id(laneId);
        if (devices.isEmpty()) {
            throw new BusinessException("No parking device assigned to lane", "PARKING_DEVICE_NOT_FOUND", 404);
        }
        if (devices.size() > 1) {
            throw new BusinessException("Lane has more than one parking device", "INVALID_LANE_DEVICE_MAPPING", 400);
        }

        ParkingDeviceEntity device = devices.get(0);
        List<BarrierEntity> barriers = barrierRepository.findAllByParkingDevice_Id(device.getId());
        Set<String> validBarrierIds = barriers.stream().map(BarrierEntity::getBarrierId).collect(Collectors.toSet());

        barrierCameraRepository.deleteByBarrierIdIn(validBarrierIds.stream().toList());

        for (BarrierCameraAssignmentRequest request : requests) {
            if (!validBarrierIds.contains(request.getBarrierId())) {
                throw new BusinessException(
                        "Barrier does not belong to lane parking device: " + request.getBarrierId(),
                        "INVALID_BARRIER_MAPPING",
                        400);
            }

            CameraEntity camera = cameraRepository.findById(request.getCameraId())
                    .orElseThrow(() -> new BusinessException(
                            "Camera not found: " + request.getCameraId(),
                            "CAMERA_NOT_FOUND",
                            404));

            camera.setLane(lane);
            cameraRepository.save(camera);

            BarrierCameraEntity mapping = new BarrierCameraEntity();
            mapping.setBarrierId(request.getBarrierId());
            mapping.setCameraId(camera.getId());
            mapping.setRole(request.getRole().trim().toLowerCase());
            mapping.setPriority(request.getPriority() != null ? request.getPriority() : 0);
            barrierCameraRepository.save(mapping);
        }
    }
    public List<LaneActivityDTO> getLaneLatestAction(Integer laneId) {
        return laneRepository.findLatestActivityPerLane(laneId);
    }
}