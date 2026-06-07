package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.BuildingCreateRequest;
import vio.barie.core.dto.BuildingResponse;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.mapper.BuildingMapper;
import vio.barie.core.repository.BuildingRepository;
import vio.barie.core.repository.LaneRepository;
import vio.barie.core.repository.ParkingLotRepository;
import vio.barie.core.repository.ParkingSessionRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingRepository buildingRepository;
    private final BuildingMapper buildingMapper;
    private final ParkingSessionRepository parkingSessionRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final LaneRepository laneRepository;

    @Transactional
    public BuildingResponse create(BuildingCreateRequest request) {
        BuildingEntity entity = buildingMapper.toEntity(request);
        BuildingEntity saved = buildingRepository.save(entity);
        return buildingMapper.toResponse(saved);
    }

    public List<BuildingResponse> findAll(BaseStatus status) {
        List<BuildingEntity> entities;
        if (status != null) {
            entities = buildingRepository.findByStatus(status);
        } else {
            entities = buildingRepository.findAll();
        }
        return buildingMapper.toResponseList(entities);
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

        if (laneRepository.existsByParkingLot_Building_Id(id)) {
            throw new BusinessException(
                    "Không thể xóa tòa nhà vì đang có làn xe trực thuộc cấu hình hệ thống. Vui lòng xóa hoặc gỡ làn xe trước!",
                    "BUILDING_HAS_LANES",
                    409
            );
        }
        if (parkingLotRepository.existsByBuildingId(id)) {
            throw new BusinessException(
                    "Không thể xóa tòa nhà vì đang chứa bãi đỗ xe hoạt động. Vui lòng gỡ bỏ bãi đỗ xe trước!",
                    "BUILDING_HAS_PARKING_LOTS",
                    409
            );
        }
        if (parkingSessionRepository.existsByParkingLot_Building_Id(id)) {
            throw new BusinessException(
                    "Không thể xóa tòa nhà này vì hệ thống đang lưu trữ dữ liệu lịch sử các phiên gửi xe liên quan!",
                    "BUILDING_HAS_PARKING_SESSIONS",
                    409
            );
        }

        buildingRepository.delete(entity);
    }
}
