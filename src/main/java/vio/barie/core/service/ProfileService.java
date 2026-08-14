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
import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    @Value("${ai.core.image-url}")
    private String imageUrlAi;
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
        String imageUrl = null;
        if (finalProfile.getImages() != null && !finalProfile.getImages().isEmpty()) {
            String rawAccessUrl = finalProfile.getImages().get(0).get("accessUrl");
            try {
                URI uri = new URI(rawAccessUrl);
                String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                imageUrl = imageUrlAi + pathAndQuery;
            } catch (Exception e) {
                log.warn("Lỗi parse URI từ AI ({}). Dùng url gốc.", rawAccessUrl);
                imageUrl = rawAccessUrl;
            }

        } else {
            log.error("AI Server không trả về thông tin ảnh (images array trống hoặc null) cho profileId: {}", validId);
            throw new RuntimeException("Không lấy được hình ảnh khuôn mặt từ AI Server. Vui lòng thử lại!");
        }
        entity.setImageUrl(imageUrl);
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
