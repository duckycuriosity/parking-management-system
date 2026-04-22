package vio.barie.core.ai;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.ProfileCreateRequest;
import vio.barie.core.exception.BusinessException;

@Service
public class AiProfileGateway {
        private final RestClient restClient;

        public AiProfileGateway(@Qualifier("aiRestClient") RestClient restClient) {
                this.restClient = restClient;
        }

        // tạo profile
        public AiProfileResponse create(ProfileCreateRequest request) {
                return restClient.post()
                                .uri("/Profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(request)
                                .retrieve()
                                .body(AiProfileResponse.class);
        }

        // lấy tấy cả
        public List<AiProfileResponse> findAll() {
                AiProfileResponse[] result = restClient.get()
                                .uri("/Profile")
                                .retrieve()
                                .body(AiProfileResponse[].class);

                if (result == null) {
                        return List.of();
                }
                return Arrays.asList(result);
        }

        // lấy theo id
        public AiProfileResponse findById(Integer id) {
                AiProfileResponse result = restClient.get()
                                .uri("/Profile/{id}", id)
                                .retrieve()
                                .body(AiProfileResponse.class);

                if (result == null) {
                        throw new BusinessException(
                                        "Profile not found with id: " + id,
                                        "PROFILE_NOT_FOUND",
                                        404);
                }
                return result;
        }

        // cập nhật
        public AiProfileResponse update(Integer id, ProfileCreateRequest request) {
                return restClient.put()
                                .uri("/Profile/{id}", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(request)
                                .retrieve()
                                .body(AiProfileResponse.class);
        }

        // Xóa profile
        public void delete(Integer id) {
                restClient.delete()
                                .uri("/Profile/{id}", id)
                                .retrieve()
                                .toBodilessEntity(); // Trả về void vì thường delete không cần body phản hồi
        }
}
