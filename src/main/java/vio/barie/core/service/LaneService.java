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

import java.util.List;

@Service
@RequiredArgsConstructor
public class LaneService {

    private final LaneRepository laneRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final LaneMapper laneMapper;

    @Transactional
    public LaneResponse create(LaneCreateRequest request) {
        ParkingLotEntity parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));

        LaneEntity entity = laneMapper.toEntity(request);
        entity.setParkingLot(parkingLot);

        LaneEntity saved = laneRepository.save(entity);
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
        return laneMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        LaneEntity entity = laneRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Lane not found", "LANE_NOT_FOUND", 404));
        laneRepository.delete(entity);
    }
}