package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.ParkingLotCreateRequest;
import vio.barie.core.dto.ParkingLotResponse;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.mapper.ParkingLotMapper;
import vio.barie.core.repository.BuildingRepository;
import vio.barie.core.repository.ParkingLotRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ParkingLotService {

    private final ParkingLotRepository parkingLotRepository;
    private final BuildingRepository buildingRepository;
    private final ParkingLotMapper parkingLotMapper;

    @Transactional
    public ParkingLotResponse create(ParkingLotCreateRequest request) {
        BuildingEntity building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new BusinessException("Building not found", "BUILDING_NOT_FOUND", 404));

        ParkingLotEntity entity = parkingLotMapper.toEntity(request);
        entity.setBuilding(building);

        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        ParkingLotEntity saved = parkingLotRepository.save(entity);
        return parkingLotMapper.toResponse(saved);

    }

    public List<ParkingLotResponse> findAll(BaseStatus status) {
        List<ParkingLotEntity> entities;
        if (status != null) {
            entities = parkingLotRepository.findByStatus(status);
        } else {
            entities = parkingLotRepository.findAll();
        }
        return parkingLotMapper.toResponseList(entities);
    }

    public ParkingLotResponse findById(Long id) {
        ParkingLotEntity entity = parkingLotRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));
        return parkingLotMapper.toResponse(entity);
    }

    public List<ParkingLotResponse> findByBuildingId(Long buildingId, BaseStatus status) {
        List<ParkingLotEntity> entities = parkingLotRepository.findByBuildingIdAndStatus(buildingId, status);
        return parkingLotMapper.toResponseList(entities);
    }

    @Transactional
    public ParkingLotResponse update(Long id, ParkingLotCreateRequest request) {
        ParkingLotEntity entity = parkingLotRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));

        BuildingEntity building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new BusinessException("Building not found", "BUILDING_NOT_FOUND", 404));

        entity.setName(request.getName());
        entity.setBuilding(building);
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        ParkingLotEntity saved = parkingLotRepository.save(entity);
        return parkingLotMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        ParkingLotEntity entity = parkingLotRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));
        parkingLotRepository.delete(entity);
    }


}
