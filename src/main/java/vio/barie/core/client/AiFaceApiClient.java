package vio.barie.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.dto.AiFaceCheckResponse;
import org.springframework.util.MultiValueMap;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.config.exception.ServiceException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class AiFaceApiClient {
    @Value("${ai.core.base-url}")
    private String baseUrl;
//
//    @Value("${ai.face.cookie-key}")
//    private String cookieKey;

    private final RestTemplate restTemplate;

    public AiFaceApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Headers dùng chung cho tất cả request
    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
////        headers.set("Cookie", "key=" + cookieKey);
//        headers.set("Origin", baseUrl);
//        headers.set("Referer", baseUrl + "/profile");
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
                String[].class);

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
        String mimeType = image.getContentType();
        if (mimeType != null && !mimeType.isEmpty()) {
            headers.setContentType(MediaType.parseMediaType(mimeType));
        } else {
            headers.setContentType(MediaType.IMAGE_JPEG);
        }

        HttpEntity<byte[]> req = new HttpEntity<>(image.getBytes(), headers);

        ResponseEntity<AiFaceCheckResponse[]> res = restTemplate.exchange(
                baseUrl + "/api/FaceCheck",
                HttpMethod.POST,
                req,
                AiFaceCheckResponse[].class);
        log.info("[FaceCheck] raw response: {}", (Object) res.getBody());

        List<AiFaceCheckResponse> faces = Arrays.asList(res.getBody());
        log.info("[FaceCheck] faces={}, prob={}",
                faces.size(),
                faces.isEmpty() ? 0 : faces.get(0).getFaceObject().getProb());
        log.info("[FaceCheck] done in {}ms", System.currentTimeMillis() - start);
        return faces;
    }


    // POST /api/FaceImageCrop?profileId={id}
    public void faceImageCrop(Integer profileId, String faceId) {
        long start = System.currentTimeMillis();
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"faceCropId\":\"%s\"}", faceId);

        log.info("[FaceImageCrop] profileId={}, body={}", profileId, body);

        org.springframework.http.RequestEntity<String> requestEntity = org.springframework.http.RequestEntity
                .post(java.net.URI.create(
                        baseUrl + "/api/FaceImageCrop?profileId=" + profileId))
                .headers(headers)
                .body(body);

        restTemplate.exchange(requestEntity, Void.class);

        log.info("[FaceImageCrop] done profileId={}", profileId);
        log.info("[FaceImageCrop] done in {}ms", System.currentTimeMillis() - start);
    }

    // → POST /api/Profile (JSON)
    public AiProfileResponse createProfile(String name, List<MultipartFile> images) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("name", name);
        appendImagesToBody(body, images);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> res = restTemplate.exchange(
                baseUrl + "/faces", HttpMethod.POST, requestEntity, String.class);
        return parseProfileResponse(res.getBody());
    }

    public AiProfileResponse getProfile(Integer profileId) {

        ResponseEntity<AiProfileResponse> res = restTemplate.exchange(
                baseUrl + "/faces/" + profileId,
                HttpMethod.GET,
                null,
                AiProfileResponse.class);
        return res.getBody();
    }

    public AiProfileResponse[] getAllProfiles() {
        ResponseEntity<AiProfileResponse[]> res = restTemplate.exchange(
                baseUrl + "/faces",
                HttpMethod.GET,
                null,
                AiProfileResponse[].class);
        return res.getBody();
    }

    public AiProfileResponse updateProfile(Integer profileId, String name, List<MultipartFile> images) {
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        if (name != null) body.add("name", name);
        appendImagesToBody(body, images);
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> res = restTemplate.exchange(
                baseUrl + "/faces/" + profileId, HttpMethod.PUT, requestEntity, String.class);
        return parseProfileResponse(res.getBody());
    }

    public void deleteProfile(Integer profileId) {
        restTemplate.exchange(
                baseUrl + "/faces/" + profileId,
                HttpMethod.DELETE,
                new HttpEntity<>(baseHeaders()),
                Void.class);
    }

    private void appendImagesToBody(MultiValueMap<String, Object> body, List<MultipartFile> images) {
        if (images != null && !images.isEmpty()) {
            if (images.size() > 5) {
                throw new ServiceException("At most five face images may be submitted", 400);
            }
            for (int i = 0; i < images.size(); i++) {
                MultipartFile file = images.get(i);
                if (file == null || file.isEmpty()) {
                    throw new ServiceException("Face image must not be empty", 400);
                }
                if (!file.isEmpty()) {
                    try {
                        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                            @Override
                            public String getFilename() {
                                return file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.jpg";
                            }
                        };
                        body.add("images", resource);
                    } catch (IOException e) {
                        throw new ServiceException("Cannot read face image before sending to Core AI", 400);
                    }
                }
            }
        }
    }

    private AiProfileResponse parseProfileResponse(String body) {
        try {
            if (body == null || body.isBlank()) {
                throw new ServiceException("Core AI returned an empty profile response", 502);
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(body);
            JsonNode profile = root.has("profile") ? root.get("profile")
                    : root.has("data") ? root.get("data") : root;
            if (profile != null && profile.isArray() && profile.size() == 1) {
                profile = profile.get(0);
            }
            if (profile == null || !profile.isObject()) {
                throw new ServiceException("Core AI returned an invalid profile response", 502);
            }
            return mapper.treeToValue(profile, AiProfileResponse.class);
        } catch (IOException e) {
            throw new ServiceException("Cannot parse Core AI profile response", e);
        }
    }
}
