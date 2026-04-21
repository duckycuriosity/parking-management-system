package vio.barie.core.ai;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.PlateCreateRequest;

import java.util.Arrays;
import java.util.List;

import static org.springframework.http.RequestEntity.post;
@Service
public class AiPlateGateway {
    private final RestClient restClient;

    public AiPlateGateway(){
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

    public AiPlateResponse create(PlateCreateRequest  request) {
        return restClient.post()
                .uri("/LicensePlate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AiPlateResponse.class);

    }


    public List<AiPlateResponse> findAll(){
        AiPlateResponse[] result = restClient.get()
                .uri("/LicensePlate")
                .retrieve()
                .body(AiPlateResponse[].class);
        return Arrays.asList(result);
    }
    // api của AI core get/{id} trả ra mang nên dùng List để trả về đúng get{id} từng cái
    public AiPlateResponse findById(Integer id) {
        List<AiPlateResponse> result = restClient.get()
                .uri("/LicensePlate/{id}", id)
                .retrieve()
                .body(new ParameterizedTypeReference<List<AiPlateResponse>>() {});
        if (result != null && !result.isEmpty()) {
            return result.get(0);
        }
        return null;
    }
    // Cập nhật thông tin biển số
    public AiPlateResponse update(Integer id, PlateCreateRequest request) {
        return restClient.put()
                .uri("/LicensePlate/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AiPlateResponse.class);
    }
    // Xóa biển số
    public void delete(Integer id) {
        restClient.delete()
                .uri("/LicensePlate/{id}", id)
                .retrieve()
                .toBodilessEntity(); // Trả về void vì thường delete không cần body phản hồi
    }

}
