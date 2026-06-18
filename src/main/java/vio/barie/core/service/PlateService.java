package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.ai.AiPlateGateway;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.PlateCreateRequest;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.mapper.PlateMapper;
import vio.barie.core.repository.VehicleRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlateService {
    private final AiPlateGateway aiPlateGateway;
    private final VehicleRepository VehicleRepository;
    private final PlateMapper plateMapper;

    @Transactional
    public AiPlateResponse create(PlateCreateRequest request) {
        if (VehicleRepository.existsByPlateNumber(request.getPlate())) {
            throw new BusinessException("Plate number already exists", "DUPLICATE_PLATE", 409);
        }
        AiPlateResponse aiResponse = aiPlateGateway.create(request);

        VehicleEntity device = plateMapper.toVehicle(aiResponse);
        VehicleEntity vehicle = VehicleRepository.save(device);
        aiResponse.setVehicle(vehicle);
        return aiResponse;
    }

    public List<AiPlateResponse> findAll() {
        return aiPlateGateway.findAll();
    }

    public AiPlateResponse findById(Integer id) {
        return aiPlateGateway.findById(id);
    }

    @Transactional
    public AiPlateResponse update(Integer id, PlateCreateRequest request) {
        // 1. Gọi sang AI Gateway để cập nhật thông tin trên server AI
        AiPlateResponse aiResponse = aiPlateGateway.update(id, request);

        // 2. Cập nhật logic ở DB cục bộ nếu cần (ví dụ tìm device theo ID AI để đồng
        // bộ)
        // Đây là nơi bạn đồng bộ dữ liệu giữa AI Server và Database của bạn

        return aiResponse;
    }

    @Transactional // Transactional nếu sau này bạn xóa cả trong DB local
    public void delete(Integer id) {
        // Logic xóa ở đây
        aiPlateGateway.delete(id);
    }
}