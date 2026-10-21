package vio.barie.core.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
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
import vio.barie.core.dto.camera.CameraStreamResponse;
import vio.barie.core.entity.BarrierCameraEntity;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.mapper.LaneMapper;
import vio.barie.core.repository.*;
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
    private final ParkingSessionRepository parkingSessionRepository;

    @Transactional
    public LaneResponse create(LaneCreateRequest request) {
        ParkingLotEntity parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));

        LaneEntity entity = laneMapper.toEntity(request);
        entity.setParkingLot(parkingLot);
        LaneEntity saved = laneRepository.save(entity);
        String exactBarrierId = null;

        if (request.getParkingDeviceCode() != null && !request.getParkingDeviceCode().trim().isEmpty()) {
            exactBarrierId = request.getParkingDeviceCode().trim();
            BarrierEntity barrier = barrierRepository.findByBarrierId(exactBarrierId)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy Barie với mã: " + request.getParkingDeviceCode(), "BARRIER_NOT_FOUND", 404));

            if (barrier.getLane() != null) {
                String errorMsg = String.format("Barie [%s] hiện tại đang được gắn ở lane: %s", barrier.getBarrierId(), barrier.getLane().getName());
                throw new BusinessException(errorMsg, "BARRIER_ALREADY_ASSIGNED_TO_LANE", 400);
            }
            barrier.setLane(saved);
            barrierRepository.save(barrier);

        }

        // validate trùng camera
        if(request.getCameraFaceId() != null && request.getCameraPlateId() != null && request.getCameraFaceId().equals(request.getCameraPlateId())){
            throw new BusinessException ("Không thể chọn cùng một Camera cho cả hai vai trò Mặt và Biển số.",
                    "DUPLICATE_CAMERA_ROLE",
                    400);
        }

        List<CameraEntity> camerasToSave = new ArrayList<>();
        CameraEntity faceCam = null;
        CameraEntity plateCam = null;

        // validate camera mặt
        if(request.getCameraFaceId() != null){
            faceCam = cameraRepository.findById(request.getCameraFaceId())
                    .orElseThrow(()-> new BusinessException("Camera mặt không tìm thấy", "CAMERA_NOT_FOUND", 404));
            if (faceCam.getLane() != null) {
                throw new BusinessException(
                        "Camera mặt này đang được cấu hình cho làn [" + faceCam.getLane().getName() + "]. Vui lòng gỡ cấu hình ở làn cũ trước.",
                        "CAMERA_ALREADY_ASSIGNED",
                        400
                );
            }
            faceCam.setLane(saved);
            camerasToSave.add(faceCam);
        }

        // validate camera biển
        if(request.getCameraPlateId() != null){
            plateCam = cameraRepository.findById(request.getCameraPlateId())
                    .orElseThrow(()-> new BusinessException("Camera biển số không tìm thấy", "CAMERA_NOT_FOUND", 404));
            if(plateCam.getLane() != null){
                throw new BusinessException(
                        "Camera biển số này đang được cấu hình cho làn [" + plateCam.getLane().getName() + "]. Vui lòng gỡ cấu hình ở làn cũ trước.",
                        "CAMERA_ALREADY_ASSIGNED",
                        400
                );
            }
            plateCam.setLane(saved);
            camerasToSave.add(plateCam);
        }

        if (!camerasToSave.isEmpty()) {
            cameraRepository.saveAll(camerasToSave);
        }

        // gán camera vào barrier
        if(exactBarrierId != null && (!camerasToSave.isEmpty())){


                if(faceCam != null){
                    BarrierCameraEntity faceMapping = new BarrierCameraEntity();
                    faceMapping.setBarrierId(exactBarrierId);
                    faceMapping.setCameraId(faceCam.getId());
                    faceMapping.setRole("FACE");
                    faceMapping.setPriority(0);
                    barrierCameraRepository.save(faceMapping);
                }

                if (plateCam != null) {
                    BarrierCameraEntity plateMapping = new BarrierCameraEntity();
                    plateMapping.setBarrierId(exactBarrierId);
                    plateMapping.setCameraId(plateCam.getId());
                    plateMapping.setRole("PLATE");
                    plateMapping.setPriority(0);
                    barrierCameraRepository.save(plateMapping);
                }

        }
        LaneResponse response = laneMapper.toResponse(saved);
        response.setCameraFaceId(request.getCameraFaceId());
        response.setCameraPlateId(request.getCameraPlateId());
        if (exactBarrierId != null) {
            response.setParkingDeviceCode(exactBarrierId);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public Page<LaneResponse> findAll(Pageable pageable) {
        Page<LaneEntity> lanePage = laneRepository.findAllWithParkingLot(pageable);
        List<LaneResponse> responses = laneMapper.toResponseList(lanePage.getContent());

        List<Long> laneIds = responses.stream().map(LaneResponse::getId).collect(Collectors.toList());
        if (!laneIds.isEmpty()) {
            List<BarrierEntity> barriers = barrierRepository.findByLaneIdIn(laneIds);
            Map<Long, BarrierEntity> barrierByLaneMap = barriers.stream()
                    .collect(Collectors.toMap(b -> b.getLane().getId(), b -> b, (existing, replacement) -> existing));

            List<String> validBarrierIds = barriers.stream()
                    .map(BarrierEntity::getBarrierId)
                    .collect(Collectors.toList());
            List<BarrierCameraEntity> allBarrierCameras = barrierCameraRepository.findByBarrierIdIn(validBarrierIds);

            Map<String, List<BarrierCameraEntity>> camerasByBarrierMap = allBarrierCameras.stream()
                    .collect(Collectors.groupingBy(BarrierCameraEntity::getBarrierId));

            for (LaneResponse res : responses) {
                BarrierEntity barrier = barrierByLaneMap.get(res.getId());
                if (barrier != null) {
                    String barrierId = barrier.getBarrierId();
                    res.setParkingDeviceCode(barrierId);

                    List<BarrierCameraEntity> barrierCameras = camerasByBarrierMap.getOrDefault(barrierId, new ArrayList<>());
                    for (BarrierCameraEntity bc : barrierCameras) {
                        if ("FACE".equalsIgnoreCase(bc.getRole())) {
                            res.setCameraFaceId(bc.getCameraId());
                        } else if ("PLATE".equalsIgnoreCase(bc.getRole())) {
                            res.setCameraPlateId(bc.getCameraId());
                        }
                    }
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

        if (entity.getBarriers() != null && !entity.getBarriers().isEmpty()) {
            BarrierEntity barrier = entity.getBarriers().get(0);
            String barrierId = barrier.getBarrierId();
            response.setParkingDeviceCode(barrierId);

            List<BarrierCameraEntity> barrierCameras = barrierCameraRepository.findByBarrierId(barrierId);
            for (BarrierCameraEntity bc : barrierCameras) {
                if ("FACE".equalsIgnoreCase(bc.getRole())) {
                    response.setCameraFaceId(bc.getCameraId());
                } else if ("PLATE".equalsIgnoreCase(bc.getRole())) {
                    response.setCameraPlateId(bc.getCameraId());
                }
            }
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

    @Transactional(readOnly = true)
    public List<CameraStreamResponse> getCamerasByLaneId(Long laneId) {
        LaneEntity entity = laneRepository.findByIdWithCameras(laneId)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));

        return laneMapper.toCameraStreamResponseList(entity.getCameras());
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

        // 1. Cập nhật Làn cho Barie
        List<BarrierEntity> currentBarriers = barrierRepository.findByLane_Id(id);
         String exactBarrierId = null;

        if (request.getParkingDeviceCode() == null || request.getParkingDeviceCode().isBlank()) {

            List<String> oldBarrierIds = currentBarriers.stream().map(BarrierEntity::getBarrierId).toList();
            for (BarrierEntity oldBarrier : currentBarriers) {
                oldBarrier.setLane(null);
            }
            if (!currentBarriers.isEmpty()) {
                barrierRepository.saveAll(currentBarriers); // BATCH SAVE
                barrierCameraRepository.deleteByBarrierIdIn(oldBarrierIds); // BATCH DELETE
            }
            barrierRepository.saveAll(currentBarriers);
        } else {
            final String barrierIdToUpdate = request.getParkingDeviceCode().trim();
            exactBarrierId = barrierIdToUpdate;
            BarrierEntity requestedBarrier = barrierRepository.findByBarrierId(barrierIdToUpdate)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy Barie với mã: " + barrierIdToUpdate, "BARRIER_NOT_FOUND", 404));

            if (requestedBarrier.getLane() != null && !requestedBarrier.getLane().getId().equals(id)) {
                String errorMsg = String.format("Barie [%s] hiện tại đang được gắn ở lane: %s",
                        requestedBarrier.getBarrierId(), requestedBarrier.getLane().getName());
                throw new BusinessException(errorMsg, "BARRIER_ALREADY_ASSIGNED_TO_LANE", 400);
            }

            // Xóa Làn của các Barie cũ không trùng khớp
            List<BarrierEntity> barriersToUnlink = new ArrayList<>();
            List<String> barrierIdsToUnlink = new ArrayList<>();
            for (BarrierEntity oldBarrier : currentBarriers) {
                if (!oldBarrier.getId().equals(requestedBarrier.getId())) {
                    oldBarrier.setLane(null);
                    barriersToUnlink.add(oldBarrier);
                    barrierIdsToUnlink.add(oldBarrier.getBarrierId());
                }
            }
            if (!barriersToUnlink.isEmpty()) {
                barrierRepository.saveAll(barriersToUnlink);
                barrierCameraRepository.deleteByBarrierIdIn(barrierIdsToUnlink);
            }
            requestedBarrier.setLane(saved);
            barrierRepository.save(requestedBarrier);
        }

        // 2. Validate và Cập nhật Camera Làn
        if (request.getCameraFaceId() != null && request.getCameraPlateId() != null && request.getCameraFaceId().equals(request.getCameraPlateId())) {
            throw new BusinessException("Không thể chọn cùng một Camera cho cả hai vai trò mặt và biển số.", "DUPLICATE_CAMERA_ROLE", 400);
        }

        List<Long> newCameraIds = new ArrayList<>();
        if (request.getCameraFaceId() != null) newCameraIds.add(request.getCameraFaceId());
        if (request.getCameraPlateId() != null) newCameraIds.add(request.getCameraPlateId());

        List<CameraEntity> currentCameras = entity.getCameras() != null ? new ArrayList<>(entity.getCameras()) : new ArrayList<>();
        List<CameraEntity> camerasToUnlink = new ArrayList<>();
        for (CameraEntity oldCam : currentCameras) {
            if (!newCameraIds.contains(oldCam.getId())) {
                oldCam.setLane(null);
                camerasToUnlink.add(oldCam);
            }
        }
        if (!camerasToUnlink.isEmpty()) {
            cameraRepository.saveAll(camerasToUnlink);
        }

        CameraEntity faceCam = null;
        CameraEntity plateCam = null;
        List<CameraEntity> camerasToSave = new ArrayList<>();

        if (request.getCameraFaceId() != null) {
            faceCam = cameraRepository.findById(request.getCameraFaceId())
                    .orElseThrow(() -> new BusinessException("Camera mặt không tìm thấy", "CAMERA_NOT_FOUND", 404));
            if (faceCam.getLane() != null && !faceCam.getLane().getId().equals(id)) {
                throw new BusinessException("Camera mặt này đang được cấu hình cho làn khác.", "CAMERA_ALREADY_ASSIGNED", 400);
            }
            faceCam.setLane(saved);
            camerasToSave.add(faceCam);
        }

        if (request.getCameraPlateId() != null) {
            plateCam = cameraRepository.findById(request.getCameraPlateId())
                    .orElseThrow(() -> new BusinessException("Camera biển số không tìm thấy.", "CAMERA_NOT_FOUND", 404));
            if (plateCam.getLane() != null && !plateCam.getLane().getId().equals(id)) {
                throw new BusinessException("Camera biển số này đang được cấu hình cho làn khác.", "CAMERA_ALREADY_ASSIGNED", 400);
            }
            plateCam.setLane(saved);
            camerasToSave.add(plateCam);
        }

        if (!camerasToSave.isEmpty()) {
            cameraRepository.saveAll(camerasToSave);
        }

        // 3. Cập nhật ánh xạ Barie - Camera
        if (exactBarrierId != null) {
            final String finalBarrierId = exactBarrierId;
            List<BarrierCameraEntity> existingMappings = barrierCameraRepository.findByBarrierId(exactBarrierId);

            if (faceCam != null) {
                BarrierCameraEntity faceMapping = existingMappings.stream()
                        .filter(m -> "FACE".equalsIgnoreCase(m.getRole()))
                        .findFirst()
                        .orElseGet(() -> {
                            BarrierCameraEntity newMapping = new BarrierCameraEntity();
                            newMapping.setBarrierId(finalBarrierId);
                            newMapping.setRole("FACE");
                            newMapping.setPriority(0);
                            return newMapping;
                        });
                faceMapping.setCameraId(faceCam.getId());
                barrierCameraRepository.save(faceMapping);
            } else {
                existingMappings.stream().filter(m -> "FACE".equalsIgnoreCase(m.getRole())).findFirst().ifPresent(barrierCameraRepository::delete);
            }

            if (plateCam != null) {
                BarrierCameraEntity plateMapping = existingMappings.stream()
                        .filter(m -> "PLATE".equalsIgnoreCase(m.getRole()))
                        .findFirst()
                        .orElseGet(() -> {
                            BarrierCameraEntity newMapping = new BarrierCameraEntity();
                            newMapping.setBarrierId(finalBarrierId);
                            newMapping.setRole("PLATE");
                            newMapping.setPriority(0);
                            return newMapping;
                        });
                plateMapping.setCameraId(plateCam.getId());
                barrierCameraRepository.save(plateMapping);
            } else {
                existingMappings.stream().filter(m -> "PLATE".equalsIgnoreCase(m.getRole())).findFirst().ifPresent(barrierCameraRepository::delete);
            }
        }

        LaneResponse response = laneMapper.toResponse(saved);
        response.setCameraFaceId(request.getCameraFaceId());
        response.setCameraPlateId(request.getCameraPlateId());
        if (exactBarrierId != null) {
            response.setParkingDeviceCode(exactBarrierId);
        }
        return response;
    }

    @Transactional
    public void delete(Long id) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> {
                    return new BusinessException("Lane not found", "LANE_NOT_FOUND", 404);
                });
        // validate check làn này đã có phiên chưa
        boolean hasParkingSessions = parkingSessionRepository.existsByLaneId(id);
        if (hasParkingSessions) {
            throw new BusinessException(
                    "Làn này đã có lịch sử xe vào/ra. Không thể xóa để bảo toàn dữ liệu đối soát. Vui lòng cập nhật trạng thái Làn thành 'Ngừng hoạt động' thay vì xóa!",
                    "LANE_HAS_SESSIONS",
                    400
            );
        }
        List<BarrierEntity> linkedBarriers = barrierRepository.findByLane_Id(id);
        if (!linkedBarriers.isEmpty()) {
            List<String> barrierIds = linkedBarriers.stream()
                    .map(BarrierEntity::getBarrierId)
                    .collect(Collectors.toList());
            throw new BusinessException("Làn này đang được gắn Barie, vui lòng gỡ cấu hình Barie trước khi xóa!", "LANE_HAS_BARRIER", 400);
        }
        if (entity.getCameras() != null && !entity.getCameras().isEmpty()) {
            List<String> cameraIds = entity.getCameras().stream()
                    .map(cam -> String.valueOf(cam.getId()))
                    .collect(Collectors.toList());
            throw new BusinessException("Làn này đang được gắn Camera, vui lòng gỡ cấu hình Camera trước khi xóa!", "LANE_HAS_CAMERA", 400);
        }
        try {
            laneRepository.delete(entity);
            laneRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("Làn này đang được liên kết với dữ liệu khác, không thể xóa!", "LANE_IN_USE", 409);
        }
    }

    @Transactional
    public void resetLaneConfiguration(Long laneId) {
        LaneEntity entity = laneRepository.findById(laneId)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));

        if (entity.getCameras() != null && !entity.getCameras().isEmpty()) {
            for (CameraEntity camera : entity.getCameras()) {
                camera.setLane(null);
            }
            cameraRepository.saveAll(entity.getCameras());

        }

        List<BarrierEntity> linkedBarriers = barrierRepository.findByLane_Id(laneId);

        if (!linkedBarriers.isEmpty()) {
            List<String> barrierIds = linkedBarriers.stream()
                    .map(BarrierEntity::getBarrierId)
                    .collect(Collectors.toList());

            barrierCameraRepository.deleteByBarrierIdIn(barrierIds);
            log.info("Đã xóa cấu hình barrier_camera cho các Barie: {}", barrierIds);

            for (BarrierEntity barrier : linkedBarriers) {
                barrier.setLane(null);
            }
            barrierRepository.saveAll(linkedBarriers);
            log.info("Đã reset lane_id về null cho các Barie thuộc Làn id: {}", laneId);
        }
        log.info("Hoàn tất reset cấu hình Làn id: {}", laneId);
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
    public void assignParkingDevices(Long laneId, java.util.List<Long> barrierIdsPK) {
        LaneEntity lane = laneRepository.findById(laneId)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        Iterable<BarrierEntity> barriers = barrierRepository.findAllById(barrierIdsPK);
        for (BarrierEntity b : barriers) {
            b.setLane(lane);
        }
        barrierRepository.saveAll(barriers);
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