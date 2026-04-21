package vio.barie.core.ai;

import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.ProfileCreateRequest;

import java.util.Arrays;
import java.util.List;

@Service
public class AiProfileGateway {
    private final RestClient restClient;

    public AiProfileGateway(){
        // Tạo HttpClient với version HTTP_1_1
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();

        ClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);

        this.restClient = RestClient.builder()
                .baseUrl("http://192.168.168.30:8088/api")
                .requestFactory(factory)
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .defaultHeader("Accept", "*/*")
                .defaultHeader("Cookie", "key=uO50EwSqoolz1OzU")
                .defaultHeader("Connection", "close")
                .build();
    }
    // tạo profile
    public AiProfileResponse create(ProfileCreateRequest request){
        return restClient.post()
                .uri("/Profile")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AiProfileResponse.class);
    }
    // lấy tấy cả
    public List<AiProfileResponse> findAll(){
        AiProfileResponse[] result = restClient.get()
                .uri("/Profile")
                .retrieve()
                .body(AiProfileResponse[].class);

        return Arrays.asList(result);
    }
    // lấy theo id
    public AiProfileResponse findById(Integer id) {
        return restClient.get()
                .uri("/Profile/{id}", id)
                .retrieve()
                .body(AiProfileResponse.class);
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
