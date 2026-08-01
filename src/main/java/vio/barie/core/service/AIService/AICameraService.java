package vio.barie.core.service.AIService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.camera.AICameraActionResponse;
import vio.barie.core.dto.camera.AICameraDetail;

import java.util.Map;

@Service
public class AICameraService {
  private final RestClient restClient;
  public AICameraService(@Qualifier("aiRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  public AICameraActionResponse addCamera (Map<String, Object> payload) {
    try {
      AICameraActionResponse response = restClient.post()
          .uri("/add_stream")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(AICameraActionResponse.class);
      if (response == null || response.getCamId() == null || response.getUuid() == null) {
        throw new ServiceException("AI service did not return cam_id and uuid");
      }
      validateActionResponse(response, "create");
      return response;
    } catch (RestClientResponseException e) {
      throw new ServiceException("AI service cannot create camera: {}");
    }
  }

  public void updateStream(String camId, Map<String, Object> payload) {
    try {
      AICameraActionResponse res =  restClient.put()
          .uri("/update_stream/{id}", camId)
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(AICameraActionResponse.class);
      validateActionResponse(res, "update");
    } catch (RestClientResponseException e) {
      throw new ServiceException("Cannot update camera to AI service");
    }
  }

  public void deleteStream (String camId) {
    try {
      restClient.delete()
          .uri(uriBuilder -> uriBuilder.path("/delete_stream").queryParam("cam_id", camId).build())
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw new ServiceException("Cannot delete camera in AI service");
    }
  }

  public Map<String, AICameraDetail> getCameras() {
    Map<String, AICameraDetail> result = restClient.get()
        .uri("/cameras")
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, AICameraDetail>>() {});
    return result != null ? result : Map.of();
  }

  private void validateActionResponse(AICameraActionResponse res, String action) {
    if (res == null || Boolean.FALSE.equals(res.getOk())) {
      throw new ServiceException("AI service notify error when " + action);
    }
    if (res.getCamId() == null || res.getCamId().isBlank()) {
      throw new ServiceException("AI service does not return cam_id, cannot mapping");
    }
  }
}
