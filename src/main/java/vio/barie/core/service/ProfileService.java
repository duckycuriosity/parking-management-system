package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.ai.AiProfileGateway;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.ProfileCreateRequest;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.mapper.ProfileMapper;
import vio.barie.core.repository.ParkingCustomerRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final AiProfileGateway aiProfileGateway;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ProfileMapper profileMapper;

    @Transactional
    public AiProfileResponse create(ProfileCreateRequest request) {

        AiProfileResponse aiResponse = aiProfileGateway.create(request);

        ParkingCustomerEntity entity = profileMapper.toEntity(aiResponse);

        parkingCustomerRepository.save(entity);

        return aiResponse;
    }

    public List<AiProfileResponse> findAll() {
        return aiProfileGateway.findAll();
    }
    public AiProfileResponse findById(Integer id) {
        // Gọi Gateway để lấy chi tiết 1 profile từ AI Server
        return aiProfileGateway.findById(id);
    }

    @Transactional
    public AiProfileResponse update(Integer id, ProfileCreateRequest request) {
        // Cập nhật thông tin trên AI Server
        AiProfileResponse aiResponse = aiProfileGateway.update(id, request);

        // Gợi ý: Nếu bạn có lưu thông tin khách hàng (tên, sđt) trong DB local,
        // bạn nên thực hiện cập nhật đồng bộ ở đây.

        return aiResponse;
    }

    @Transactional
    public void delete(Integer id) {
        // 1. Xóa trên AI Server
        aiProfileGateway.delete(id);

        // 2. Xóa trong Database local dựa trên ID AI (UUID) để tránh rác dữ liệu
        // Ví dụ: parkingCustomerRepository.deleteByAiIdentityId(id.toString());
    }
}
