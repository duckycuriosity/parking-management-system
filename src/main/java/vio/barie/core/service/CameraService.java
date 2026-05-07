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
import vio.barie.core.dto.vms.LiveStreamRequestDTO;
import org.springframework.stereotype.Service;

@Service
public class CameraService {
    private final RestClient restClient;
    private final VmsTokenService vmsTokenService;

    @Value("${vms.core.base-url}")
    private String vmsApiUrl;

    @Value("${vms.camerasEndpoint:/Camera}")
    private String camerasEndpoint;

    @Value("${vms.liveStreamEndpoint:/LiveStream}")
    private String liveStreamEndpoint;

    public CameraService(
            @Qualifier("vmsRestClient") RestClient restClient,
            VmsTokenService vmsTokenService
    ) {
        this.restClient = restClient;
        this.vmsTokenService = vmsTokenService;
    }


    public JsonNode startLiveSteam(LiveStreamRequestDTO request) {
        return vmsTokenService.executeWithAutoLoginRetry(token -> {
            String url = vmsApiUrl + liveStreamEndpoint;

            System.out.println("===== START STREAM =====");
            System.out.println("URL: " + url);
            System.out.println("TOKEN: " + token);
            System.out.println("BODY: " + request);

            try {
                JsonNode response = restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ACCEPT, "*/*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);

                System.out.println("RESPONSE: " + response);
                return response;

            } catch (HttpStatusCodeException e) {
                System.out.println("ERROR CALL VMS:");
                e.printStackTrace();

                throw new ClientException(
                        e.getResponseBodyAsString(),
                        e.getStatusCode().value()
                );
            }
        });
    }

    public JsonNode createCamera(JsonNode clientBody) {
        return vmsTokenService.executeWithAutoLoginRetry(token -> {
            String url = vmsApiUrl + camerasEndpoint;

            System.out.println("===== CREATE CAMERA =====");
            System.out.println("URL: " + url);
            System.out.println("TOKEN: " + token);
            System.out.println("BODY: " + clientBody);

            try {
                JsonNode response = restClient.post()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(clientBody)
                        .retrieve()
                        .body(JsonNode.class);

                System.out.println("RESPONSE: " + response);
                return response;

            } catch (HttpStatusCodeException e) {
                System.out.println("ERROR CALL VMS:");
                e.printStackTrace();

                throw new ClientException(
                        e.getResponseBodyAsString(),
                        e.getStatusCode().value()
                );
            }
        });
    }

    public JsonNode getCameraList() {
        return vmsTokenService.executeWithAutoLoginRetry(token -> {
            String url = vmsApiUrl + camerasEndpoint;

            System.out.println("===== DEBUG VMS CALL =====");
            System.out.println("URL: " + url);
            System.out.println("TOKEN: " + token);

            try {
                JsonNode response = restClient.get()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class);

                System.out.println("RESPONSE: " + response);
                return response;

            } catch (HttpStatusCodeException e) {
                System.out.println("ERROR CALL VMS:");
                e.printStackTrace();

                throw new ClientException(
                        e.getResponseBodyAsString(),
                        e.getStatusCode().value()
                );
            }
        });
    }


}