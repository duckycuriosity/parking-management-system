package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.VerificationMode;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.ParkingLotCreateRequest;
import vio.barie.core.dto.ParkingLotResponse;
import vio.barie.core.entity.BuildingEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.mapper.ParkingLotMapper;
import vio.barie.core.repository.BuildingRepository;
import vio.barie.core.repository.ParkingLotRepository;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ParkingLotService {

    private final ParkingLotRepository parkingLotRepository;
    private final BuildingRepository buildingRepository;
    private final ParkingLotMapper parkingLotMapper;

    private String normalizeString(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }
        // Chuẩn hóa loại bỏ dấu
        String normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String noAccent = pattern.matcher(normalized).replaceAll("");

        return noAccent.replace("đ", "d").replace("Đ", "d").toLowerCase();
    }

    private void validateDuplicateName(String newName, Long buildingId, Long excludeId) {
        String normalizedNewName = normalizeString(newName);

        List<String> existingNames = parkingLotRepository.findAllNamesByBuildingExcludingId(buildingId, excludeId);

        boolean isDuplicate = existingNames.stream()
                .anyMatch(name -> normalizeString(name).equals(normalizedNewName));

        if (isDuplicate) {
            throw new BusinessException("Tên bãi xe đã tồn tại trong hệ thống (trùng lặp cả khi không dấu)!", "PARKING_LOT_DUPLICATED", 400);
        }
    }

    @Transactional
    public ParkingLotResponse create(ParkingLotCreateRequest request) {

        validateDuplicateName(request.getName(), request.getBuildingId(), null);
        BuildingEntity building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new BusinessException("Building not found", "BUILDING_NOT_FOUND", 404));

        ParkingLotEntity entity = parkingLotMapper.toEntity(request);
        entity.setBuilding(building);

        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        entity.setMaxMotorcycleCapacity(request.getMaxMotorcycleCapacity());
        entity.setMaxCarCapacity(request.getMaxCarCapacity());
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
        validateDuplicateName(request.getName(), request.getBuildingId(), id);
        entity.setName(request.getName());
        entity.setBuilding(building);
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        if (request.getMaxMotorcycleCapacity() != null) {
            entity.setMaxMotorcycleCapacity(request.getMaxMotorcycleCapacity());
        }
        if (request.getMaxCarCapacity() != null) {
            entity.setMaxCarCapacity(request.getMaxCarCapacity());

        }
        if (request.getVerificationMode() != null) {
            entity.setVerificationMode(request.getVerificationMode());
        }
        ParkingLotEntity saved = parkingLotRepository.save(entity);
        return parkingLotMapper.toResponse(saved);
    }

    @Transactional
    public ParkingLotResponse updateVerificationMode(Long id, VerificationMode verificationMode) {
        ParkingLotEntity entity = parkingLotRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Parking lot not found", "PARKING_LOT_NOT_FOUND", 404));
        entity.setVerificationMode(verificationMode);
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
