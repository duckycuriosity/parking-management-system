package vio.barie.core.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.dto.AiFaceCheckResponse;
import vio.barie.core.dto.AiProfileResponse;


import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class AiFaceApiClient {
    @Value("${ai.face.base-url}")
    private String baseUrl;

    @Value("${ai.face.cookie-key}")
    private String cookieKey;

    private final RestTemplate restTemplate;

    public AiFaceApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Headers dùng chung cho tất cả request
    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Cookie", "key=" + cookieKey);
        headers.set("Origin", baseUrl);
        headers.set("Referer", baseUrl + "/profile");
        headers.set("Accept", "*/*");
        return headers;
    }

    // POST /api/FaceCrop (image/jpeg binary)
    public String faceCrop(MultipartFile image) throws IOException {
        long start = System.currentTimeMillis();
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);

        HttpEntity<byte[]> req = new HttpEntity<>(image.getBytes(), headers);

        ResponseEntity<String[]> res = restTemplate.exchange(
                baseUrl + "/api/FaceCrop",
                HttpMethod.POST,
                req,
                String[].class
        );

        String faceId = res.getBody()[0];
        log.info("[FaceCrop] faceId={}", faceId);

        if (faceId == null) {
            throw new RuntimeException("FaceCrop trả về null");
        }
        log.info("[FaceCrop] done in {}ms", System.currentTimeMillis() - start);
        return faceId;
    }

    // POST /api/FaceCheck (image/jpeg binary)
    public List<AiFaceCheckResponse> faceCheck(MultipartFile image) throws IOException {
        long start = System.currentTimeMillis();
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);

        HttpEntity<byte[]> req = new HttpEntity<>(image.getBytes(), headers);

        ResponseEntity<AiFaceCheckResponse[]> res = restTemplate.exchange(
                baseUrl + "/api/FaceCheck",
                HttpMethod.POST,
                req,
                AiFaceCheckResponse[].class
        );
        log.info("[FaceCheck] raw response: {}", res.getBody());

        List<AiFaceCheckResponse> faces = Arrays.asList(res.getBody());
        log.info("[FaceCheck] faces={}, prob={}",
                faces.size(),
                faces.isEmpty() ? 0 : faces.get(0).getFaceObject().getProb());
        log.info("[FaceCheck] done in {}ms", System.currentTimeMillis() - start);
        return faces;
    }

    // → POST /api/Profile (JSON)
    public AiProfileResponse createProfile(String name, Integer groupId) {
        long start = System.currentTimeMillis();
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"name\":\"%s\",\"groupId\":%d}", name, groupId);

        ResponseEntity<AiProfileResponse> res = restTemplate.exchange(
                baseUrl + "/api/Profile",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                AiProfileResponse.class
        );

        log.info("[CreateProfile] raw response: {}", res.getBody());
        log.info("[CreateProfile] done in {}ms", System.currentTimeMillis() - start);
        return res.getBody();
    }

    //    POST /api/FaceImageCrop?profileId={id}
    public void faceImageCrop(Integer profileId, String faceId) {
        long start = System.currentTimeMillis();
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"faceCropId\":\"%s\"}", faceId);

        log.info("[FaceImageCrop] profileId={}, body={}", profileId, body);

        org.springframework.http.RequestEntity<String> requestEntity =
                org.springframework.http.RequestEntity
                        .post(java.net.URI.create(
                                baseUrl + "/api/FaceImageCrop?profileId=" + profileId))
                        .headers(headers)
                        .body(body);

        restTemplate.exchange(requestEntity, Void.class);

        log.info("[FaceImageCrop] done profileId={}", profileId);
        log.info("[FaceImageCrop] done in {}ms", System.currentTimeMillis() - start);
    }

    public AiProfileResponse getProfile(Integer profileId) {
        long start = System.currentTimeMillis();
        log.info("[GetProfile] calling... profileId={}", profileId);
        ResponseEntity<AiProfileResponse> res = restTemplate.exchange(
                baseUrl + "/api/Profile/" + profileId,
                HttpMethod.GET,
                new HttpEntity<>(baseHeaders()),
                AiProfileResponse.class
        );
        log.info("[GetProfile] done in {}ms", System.currentTimeMillis() - start);
        return res.getBody();
    }


    public AiProfileResponse[] getAllProfiles() {
        ResponseEntity<AiProfileResponse[]> res = restTemplate.exchange(
                baseUrl + "/api/Profile",
                HttpMethod.GET,
                new HttpEntity<>(baseHeaders()),
                AiProfileResponse[].class
        );
        return res.getBody();
    }

    public AiProfileResponse updateProfile(Integer profileId, String name, Integer groupId) {
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"id\":%d,\"name\":\"%s\",\"groupId\":%d}",
                profileId, name, groupId);

        ResponseEntity<AiProfileResponse> res = restTemplate.exchange(
                baseUrl + "/api/Profile/" + profileId,
                HttpMethod.PUT,
                new HttpEntity<>(body, headers),
                AiProfileResponse.class
        );
        return res.getBody();
    }

    public void deleteProfile(Integer profileId) {
        restTemplate.exchange(
                baseUrl + "/api/Profile/" + profileId,
                HttpMethod.DELETE,
                new HttpEntity<>(baseHeaders()),
                Void.class
        );
    }


}
