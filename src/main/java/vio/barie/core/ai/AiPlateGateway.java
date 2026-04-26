package vio.barie.core.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.PlateCreateRequest;

import java.util.Arrays;
import java.util.List;

@Service
public class AiPlateGateway {
    private final RestClient restClient;

    public AiPlateGateway(@Qualifier("aiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public AiPlateResponse create(PlateCreateRequest request) {
        return restClient.post()
                .uri("/LicensePlate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AiPlateResponse.class);
    }

    public List<AiPlateResponse> findAll() {
        AiPlateResponse[] result = restClient.get()
                .uri("/LicensePlate")
                .retrieve()
                .body(AiPlateResponse[].class);
        if (result == null) {
            return List.of();
        }
        return Arrays.asList(result);
    }

    // api của AI core get/{id} trả ra mang nên dùng List để trả về đúng get{id}
    // từng cái
    public AiPlateResponse findById(Integer id) {
        List<AiPlateResponse> result = restClient.get()
                .uri("/LicensePlate/{id}", id)
                .retrieve()
                .body(new ParameterizedTypeReference<List<AiPlateResponse>>() {
                });
        if (result != null && !result.isEmpty()) {
            return result.get(0);
        }
        throw new ServiceException(
                "Plate not found with id: " + id,
                404);
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
