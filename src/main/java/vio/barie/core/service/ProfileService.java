package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final ProfileMapper profileMapper;
    private final AiFaceApiClient aiFaceApiClient;

    @Transactional
    public AiProfileResponse create(String name, Integer groupId, MultipartFile image)
            throws IOException {

        // BƯỚC 1: Crop face
        String faceId = aiFaceApiClient.faceCrop(image);

        // BƯỚC 2: Tạo profile NGAY (trước khi faceId expire)
        AiProfileResponse created = aiFaceApiClient.createProfile(name, groupId);
        Integer profileId = created.getId();

        // BƯỚC 3: Gắn ảnh vào profile NGAY sau FaceCrop
        aiFaceApiClient.faceImageCrop(profileId, faceId);

        // BƯỚC 4: FaceCheck sau cùng (không cần thiết cho flow tạo)
        List<AiFaceCheckResponse> faces = aiFaceApiClient.faceCheck(image);
        if (faces.isEmpty()) {
            throw new RuntimeException("Không phát hiện khuôn mặt trong ảnh");
        }

        // Verify
        AiProfileResponse finalProfile = aiFaceApiClient.getProfile(profileId);

  
        ParkingCustomerEntity entity = profileMapper.toEntity(finalProfile);
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
                                    MultipartFile image) throws IOException {
        // Nếu có ảnh mới → crop và gắn lại
        if (image != null && !image.isEmpty()) {
            String faceId = aiFaceApiClient.faceCrop(image);
            aiFaceApiClient.faceImageCrop(id, faceId);
        }

        // Cập nhật tên trên AI server
        return aiFaceApiClient.updateProfile(id, name, groupId);
    }

    @Transactional
    public void delete(Integer id) {
        aiFaceApiClient.deleteProfile(id);

    }
}
