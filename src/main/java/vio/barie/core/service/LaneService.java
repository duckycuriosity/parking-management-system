package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.LaneCreateRequest;
import vio.barie.core.dto.LaneResponse;
import vio.barie.core.entity.LaneEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.exception.BusinessException;
import vio.barie.core.mapper.LaneMapper;
import vio.barie.core.repository.LaneRepository;
import vio.barie.core.repository.ParkingLotRepository;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.entity.ParkingDeviceEntity;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LaneService {

    private final LaneRepository laneRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final LaneMapper laneMapper;
    private final CameraRepository cameraRepository;
    private final ParkingDeviceRepository parkingDeviceRepository;

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
            Iterable<ParkingDeviceEntity> devs = parkingDeviceRepository.findAllById(request.getParkingDeviceIds());
            for (ParkingDeviceEntity d : devs) {
                d.setLane(saved);
            }
            parkingDeviceRepository.saveAll(devs);
        }

        return laneMapper.toResponse(saved);
    }

    public List<LaneResponse> findAll() {
        return laneMapper.toResponseList(laneRepository.findAll());
    }

    public LaneResponse findById(Long id) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        return laneMapper.toResponse(entity);
    }

    @Transactional
    public LaneResponse update(Long id, LaneCreateRequest request) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));

        ParkingLotEntity parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));

        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setStatus(request.getStatus());
        entity.setParkingLot(parkingLot);

        LaneEntity saved = laneRepository.save(entity);

        // update camera associations (simple strategy: set lane for listed cameras)
        if (request.getCameraIds() != null) {
            Iterable<CameraEntity> cams = cameraRepository.findAllById(request.getCameraIds());
            for (CameraEntity cam : cams) {
                cam.setLane(saved);
            }
            cameraRepository.saveAll(cams);
        }

        if (request.getParkingDeviceIds() != null) {
            Iterable<ParkingDeviceEntity> devs = parkingDeviceRepository.findAllById(request.getParkingDeviceIds());
            for (ParkingDeviceEntity d : devs) {
                d.setLane(saved);
            }
            parkingDeviceRepository.saveAll(devs);
        }

        return laneMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        laneRepository.delete(entity);
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
}