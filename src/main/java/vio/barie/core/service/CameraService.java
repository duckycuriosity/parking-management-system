package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import vio.barie.core.config.errorException.ClientException;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import vio.barie.core.dto.vms.LiveStreamRequestDTO;
import org.springframework.stereotype.Service;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;

import java.util.List;

@Service
public class CameraService {
    private final RestClient restClient;
    // private final VmsTokenService vmsTokenService;
    private final CameraRepository cameraRepository;
    private final CameraMapper cameraMapper;

    @Value("${vms.core.base-url}")
    private String vmsApiUrl;

    @Value("${vms.camerasEndpoint:/Camera}")
    private String camerasEndpoint;

    @Value("${vms.liveStreamEndpoint:/LiveStream}")
    private String liveStreamEndpoint;

    public CameraService(
            // @Qualifier("vmsRestClient") RestClient restClient,
            // VmsTokenService vmsTokenService,
            @Qualifier("aiRestClient") RestClient restClient,
            CameraRepository cameraRepository,
            CameraMapper cameraMapper

    ) {
        this.restClient = restClient;
        this.cameraRepository = cameraRepository;
        this.cameraMapper = cameraMapper;
        // this.vmsTokenService = vmsTokenService;
    }

    // public JsonNode startLiveSteam(LiveStreamRequestDTO request) {
    // return vmsTokenService.executeWithAutoLoginRetry(token -> {
    // String url = vmsApiUrl + liveStreamEndpoint;
    //
    // System.out.println("===== START STREAM =====");
    // System.out.println("URL: " + url);
    // System.out.println("TOKEN: " + token);
    // System.out.println("BODY: " + request);
    //
    // try {
    // JsonNode response = restClient.post()
    // .uri(url)
    // .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
    // .header(HttpHeaders.ACCEPT, "*/*")
    // .contentType(MediaType.APPLICATION_JSON)
    // .body(request)
    // .retrieve()
    // .body(JsonNode.class);
    //
    // System.out.println("RESPONSE: " + response);
    // return response;
    //
    // } catch (HttpStatusCodeException e) {
    // System.out.println("ERROR CALL VMS:");
    // e.printStackTrace();
    //
    // throw new ClientException(
    // e.getResponseBodyAsString(),
    // e.getStatusCode().value()
    // );
    // }
    // });
    // }

    public CameraResponse createCamera(CameraCreateRequest clientBody) {
        // return vmsTokenService.executeWithAutoLoginRetry(token -> {
        // String url = vmsApiUrl + camerasEndpoint;
        //
        // try {
        // JsonNode response = restClient.post()
        // .uri(url)
        // .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        // .contentType(MediaType.APPLICATION_JSON)
        // .accept(MediaType.APPLICATION_JSON)
        // .body(clientBody)
        // .retrieve()
        // .body(JsonNode.class);
        //
        // return response;
        //
        // } catch (HttpStatusCodeException e) {
        // System.out.println("ERROR CALL VMS:");
        // e.printStackTrace();
        //
        // throw new ClientException(
        // e.getResponseBodyAsString(),
        // e.getStatusCode().value()
        // );
        // }
        // });

        CameraEntity camera = new CameraEntity();
        JsonNode aiCamera = restClient.post()
                .uri("/Camera")
                .contentType(MediaType.APPLICATION_JSON)
                .body(clientBody)
                .retrieve()
                .body(JsonNode.class);
        camera.setAiCameraId(aiCamera.path("id").asLong());
        camera.setName(aiCamera.path("name").asText());
        camera.setUuid(aiCamera.path("uuid").asText());
        camera.setUrl(aiCamera.path("url").asText());
        camera.setType(aiCamera.path("type").asText());
        CameraEntity saved = cameraRepository.save(camera);

        return cameraMapper.toResponse(saved);
    }

    public List<CameraResponse> getCameraList() {
        List<CameraEntity> entities = cameraRepository.findAll();
        return cameraMapper.toResponseList(entities);

    }

}