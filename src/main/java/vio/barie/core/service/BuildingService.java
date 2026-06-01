package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.BuildingCreateRequest;
import vio.barie.core.dto.BuildingResponse;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.mapper.BuildingMapper;
import vio.barie.core.repository.BuildingRepository;
import vio.barie.core.repository.ParkingSessionRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingRepository buildingRepository;
    private final BuildingMapper buildingMapper;
    private final ParkingSessionRepository parkingSessionRepository;

    @Transactional
    public BuildingResponse create(BuildingCreateRequest request) {
        BuildingEntity entity = buildingMapper.toEntity(request);
        BuildingEntity saved = buildingRepository.save(entity);
        return buildingMapper.toResponse(saved);
    }

    public List<BuildingResponse> findAll() {
        return buildingMapper.toResponseList(buildingRepository.findAll());
    }

    public BuildingResponse findById(Long id) {
        BuildingEntity entity = buildingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Building not found", "BUILDING_NOT_FOUND", 404));
        return buildingMapper.toResponse(entity);
    }

    @Transactional
    public BuildingResponse update(Long id, BuildingCreateRequest request) {
        BuildingEntity entity = buildingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Building not found", "BUILDING_NOT_FOUND", 404));

        entity.setName(request.getName());
        entity.setAddress(request.getAddress());
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        BuildingEntity saved = buildingRepository.save(entity);
        return buildingMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        BuildingEntity entity = buildingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Building not found", "BUILDING_NOT_FOUND", 404));

        if (parkingSessionRepository.existsByParkingLot_Building_Id(id)) {
            throw new BusinessException(
                    "Cannot delete building because it still has parking session history",
                    "BUILDING_HAS_PARKING_SESSIONS",
                    409
            );
        }

        buildingRepository.delete(entity);
    }
}
