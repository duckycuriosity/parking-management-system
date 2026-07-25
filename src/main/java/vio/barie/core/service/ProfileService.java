package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.ai.AiProfileGateway;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.dto.AiFaceCheckResponse;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.mapper.ProfileMapper;
import vio.barie.core.repository.ParkingCustomerRepository;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    @Value("${ai.face.face-image-url:http://192.168.168.30:8088/api/FaceImage/}")
    private String customerFaceImageUrl;

    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ProfileMapper profileMapper;
    private final AiFaceApiClient aiFaceApiClient;

    @Transactional
    public AiProfileResponse create(String name, Integer groupId, List<MultipartFile> images, String email)
            throws IOException {
        if (images != null && images.size() > 5) {
            throw new IllegalArgumentException("Hệ thống chỉ hỗ trợ tải lên tối đa 5 ảnh.");
        }
        AiProfileResponse finalProfile = aiFaceApiClient.createProfile(name, images);
        if (finalProfile == null || (finalProfile.getId() == null && finalProfile.getAiProfileId() == null)) {
            throw new RuntimeException("Tạo profile AI thất bại");
        }
        if (finalProfile.getId() == null && finalProfile.getAiProfileId() == null) {
            throw new RuntimeException("Tạo profile AI thất bại (Không map được ID)");
        }
        Integer validId = finalProfile.getId() != null ? finalProfile.getId() : finalProfile.getAiProfileId();
        finalProfile.setId(validId);
        ParkingCustomerEntity entity = profileMapper.toEntity(finalProfile);
        entity.setImageUrl(customerFaceImageUrl + finalProfile.getId());
        entity.setEmail(email);
        parkingCustomerRepository.save(entity);
        return finalProfile;
    }

    public List<AiProfileResponse> findAll() {
        return Arrays.asList(aiFaceApiClient.getAllProfiles());
    }

    public AiProfileResponse findById(Integer id) {
        return aiFaceApiClient.getProfile(id);
    }

    @Transactional
    public AiProfileResponse update(Integer id, String name, Integer groupId,
                                    List<MultipartFile> images) throws IOException {

        if (images != null && images.size() > 5) {
            throw new IllegalArgumentException("Hệ thống chỉ hỗ trợ tải lên tối đa 5 ảnh.");
        }
        return aiFaceApiClient.updateProfile(id, name, images);
    }

    @Transactional
    public void delete(Integer id) {
        aiFaceApiClient.deleteProfile(id);

    }
}
