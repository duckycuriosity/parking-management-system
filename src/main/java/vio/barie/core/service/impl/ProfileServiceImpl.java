package vio.barie.core.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.ai.AiProfileGateway;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.ProfileCreateRequest;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.mapper.ProfileMapper;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.service.ProfileService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {
    private final AiProfileGateway aiProfileGateway;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ProfileMapper profileMapper;

    @Override
    @Transactional
    public AiProfileResponse create(ProfileCreateRequest request) {

        AiProfileResponse aiResponse = aiProfileGateway.create(request);

        ParkingCustomerEntity entity = profileMapper.toEntity(aiResponse);

        parkingCustomerRepository.save(entity);

        return aiResponse;
    }

    @Override
    public List<AiProfileResponse> findAll() {
        return aiProfileGateway.findAll();
    }
    @Override
    public AiProfileResponse findById(Integer id) {
        // Gọi Gateway để lấy chi tiết 1 profile từ AI Server
        return aiProfileGateway.findById(id);
    }

    @Override
    @Transactional
    public AiProfileResponse update(Integer id, ProfileCreateRequest request) {
        // Cập nhật thông tin trên AI Server
        AiProfileResponse aiResponse = aiProfileGateway.update(id, request);

        // Gợi ý: Nếu bạn có lưu thông tin khách hàng (tên, sđt) trong DB local,
        // bạn nên thực hiện cập nhật đồng bộ ở đây.

        return aiResponse;
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        // 1. Xóa trên AI Server
        aiProfileGateway.delete(id);

        // 2. Xóa trong Database local dựa trên ID AI (UUID) để tránh rác dữ liệu
        // Ví dụ: parkingCustomerRepository.deleteByAiIdentityId(id.toString());
    }
}
