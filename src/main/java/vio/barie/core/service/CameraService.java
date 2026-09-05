package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.*;
import org.springframework.stereotype.Service;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.service.AIService.AICameraService;


import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CameraService {

    private final RestClient aiRestClient;
    private final CameraRepository cameraRepository;
    private final CameraMapper cameraMapper;
    private final ObjectMapper objectMapper;
    private final AICameraService aiCameraService;

    @Value("${ai.core.base-url}")
    private String aiApiUrl;

    @Value("${ai.core.stream-url}")
    private String streamAI;

    public CameraService(
            @Qualifier("aiRestClient") RestClient aiRestClient,
            AICameraService aiCameraService,
            CameraRepository cameraRepository,
            CameraMapper cameraMapper,
            ObjectMapper objectMapper

    ) {
        this.aiRestClient = aiRestClient;
        this.cameraRepository = cameraRepository;
        this.aiCameraService = aiCameraService;
        this.cameraMapper = cameraMapper;
        this.objectMapper = objectMapper;
    }


    public CameraResponse createCamera(CameraCreateRequest clientBody) {

        Map<String, Object> aiPayload = new java.util.HashMap<>();

        aiPayload.put("name", clientBody.getName());
        aiPayload.put("url", clientBody.getMain() != null ? clientBody.getMain().trim() : "");
        aiPayload.put("mode", clientBody.getMode().getAiValue());
        aiPayload.put("protocol", clientBody.getProtocol());

        AICameraActionResponse aiCameraResponse = aiCameraService.addCamera(aiPayload);

        CameraEntity camera = new CameraEntity();
        camera.setName(clientBody.getName());
        camera.setUrl(clientBody.getMain() != null ? clientBody.getMain().trim() : "");
        camera.setAiCameraId(aiCameraResponse.getCamId());
        camera.setUuid(aiCameraResponse.getUuid());
        camera.setLatitude(clientBody.getLatitude() != null ? clientBody.getLatitude() : 0.0);
        camera.setLongitude(clientBody.getLongitude() != null ? clientBody.getLongitude() : 0.0);
        camera.setSubUrl(clientBody.getSub());
        camera.setMode(clientBody.getMode().getAiValue());
        camera.setProtocol(clientBody.getProtocol());
        camera.setType(BaseStatus.ACTIVE);
        camera.setStatus(BaseStatus.ACTIVE);

        try {
            CameraEntity saved = cameraRepository.save(camera);
            return cameraMapper.toResponse(saved);
        } catch (RuntimeException e) {
            log.error("Save camera fail after call create camera to AI service cam_id={}", aiCameraResponse.getCamId());
            try {
                aiCameraService.deleteStream(aiCameraResponse.getCamId());
            } catch (Exception rollback) {
                log.error("Rollback AI service failed");
            }
            throw e;
        }
    }


    public List<CameraResponse> getCameraList(String searchKey, BaseStatus status) {
        String formattedSearchKey = (searchKey != null && !searchKey.trim().isEmpty()) ? "%" + searchKey.trim() + "%" : null;

        List<CameraEntity> entities = cameraRepository.searchCameras(formattedSearchKey, null);
        List<CameraResponse> responses = cameraMapper.toResponseList(entities);
        Map<String, AICameraDetail> aiCameras = fetchAICamerasSafely();

        for(CameraResponse res : responses){
            AICameraDetail detail = aiCameras.get(res.getAiCameraId());
            if (detail != null) {
                res.setAiStreamStatus(detail.getStatus());
                res.setAiStreamError(detail.getError());
                res.setFps(detail.getFps());
                if(detail.getLiveStream() != null){
                    res.setLiveStreamLink(detail.getLiveStream().getLink());
                    res.setAudioCodec(detail.getLiveStream().getAudioCodec());
                    res.setVideoCodec(detail.getLiveStream().getVideoCodec());
                }
            } else {
                res.setAiStreamStatus("unknown");
            }

        }
        if (status != null) {
            return responses.stream().filter(res -> res.getStatus() == status).toList();
        }
        return responses;
    }

    public CameraResponse getCameraById(Long id) {
        CameraEntity entity = cameraRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + id));
        return cameraMapper.toResponse(entity);
    }

    public CameraResponse updateCamera(Long id, CameraUpdateRequest request) {
        CameraEntity entity = cameraRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + id));

        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getMain() != null) {
            entity.setUrl(request.getMain().trim());
        }
        if (request.getSub() != null) {
            entity.setSubUrl(request.getSub());
        }
        if (request.getLatitude() != null) {
            entity.setLatitude(request.getLatitude());
        }
        if (request.getLongitude() != null) {
            entity.setLongitude(request.getLongitude());
        }
        if (request.getMode() != null) {
            entity.setMode(request.getMode().getAiValue());
        }
        if (request.getProtocol() != null) {
            entity.setProtocol(request.getProtocol());
        }

        String aiCameraId = entity.getAiCameraId();
        if (aiCameraId != null && !aiCameraId.isBlank()) {
            Map<String, Object> aiPayload = new java.util.HashMap<>();
            aiPayload.put("name", entity.getName());
            aiPayload.put("url", entity.getUrl());
            aiPayload.put("mode", entity.getMode());
            aiPayload.put("protocol", entity.getProtocol());
            aiCameraService.updateStream(aiCameraId, aiPayload);
        } else {
            log.warn("Camera id={} does not map AI service, but update DB", id);
        }
        CameraEntity updated = cameraRepository.save(entity);
        return cameraMapper.toResponse(updated);
    }

    @Transactional
    public void deleteCamera(Long id) {
        CameraEntity entity = cameraRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + id));
        String aiCameraId = entity.getAiCameraId();
        if (aiCameraId != null && !aiCameraId.isBlank()) {
            aiCameraService.deleteStream(aiCameraId);
        } else {
            log.warn("Camera id={} does not exists", id);
        }
        cameraRepository.deleteById(id);
    }

    public ResponseEntity<StreamingResponseBody> aiVideoStream(String aiCameraId) {

        cameraRepository.findCameraByAiCameraId(aiCameraId)
                .orElseThrow(() -> new RuntimeException("Camera không tồn tại với ID AI: " + aiCameraId));

        String cleanId = aiCameraId.replaceAll("[\\r\\n\\t ]", "");
        String streamUrl = streamAI + "/video_feed/" + cleanId;

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "*/*")
                    .GET()
                    .build();

            HttpResponse<InputStream> aiResponse = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (aiResponse.statusCode() != 200) {
                log.error("[STREAM] AI Server từ chối kết nối, HTTP Code: {}", aiResponse.statusCode());
                return ResponseEntity.badRequest().build();
            }

            String aiContentType = aiResponse.headers().firstValue("Content-Type")
                    .orElse("multipart/x-mixed-replace; boundary=frame");
            StreamingResponseBody stream = out ->{
                try(InputStream is = aiResponse.body()){
                    is.transferTo(out);
                }catch (Exception e){
                    log.warn("[STREAM] Ngắt kết nối stream ");
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, aiContentType)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, max-age=0")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header(HttpHeaders.CONNECTION, "keep-alive")
                    .body(stream);

        } catch (Exception e) {
            log.error("Lỗi khi stream video từ AI", e);
           return ResponseEntity.badRequest().build();
        }
    }
//
//    public ReportLinkResponse getReportLink(Integer identityId) {
//        ReportLinkResponse response = vmsTokenService.executeWithAutoLoginRetry(token ->
//                vmsRestClient.get()
//                        .uri(vmsApiUrl + "/ReportLink/" + identityId)
//                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
//                        .accept(MediaType.APPLICATION_JSON)
//                        .retrieve()
//                        .body(ReportLinkResponse.class)
//        );
//
//        // chuẩn hóa link WebSocket tự động bằng properties
//        if (response != null && response.getLink() != null) {
//            String normalizedLink = response.getLink().replace(internalWsHost, publicWsHost);
//            response.setLink(normalizedLink);
//        }
//
//        return response;
//    }

    @Transactional
    public List<CameraResponse> syncCamerasFromAI() {
        String rawResponse;
        try {
            rawResponse = aiRestClient.get()
                    .uri(aiApiUrl + "/cameras")
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new RuntimeException("Không thể kết nối tới AI API", e);
        }
        java.util.List<CameraEntity> syncedCameras = new java.util.ArrayList<>();
        if (rawResponse == null || rawResponse.isBlank()) {
            return cameraMapper.toResponseList(syncedCameras);
        }

        try {
            JsonNode aiCamerasNode = objectMapper.readTree(rawResponse);
            if (aiCamerasNode.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = aiCamerasNode.fields();

                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String aiCamId = field.getKey();
                    JsonNode camData = field.getValue();
                    if (aiCamId == null || aiCamId.isBlank()) continue;
                    CameraEntity entity = cameraRepository.findCameraByAiCameraId(aiCamId)
                            .orElseGet(CameraEntity::new);

                    entity.setAiCameraId(aiCamId); // từ bigint sang varchar
                    entity.setName(camData.path("name").asText());
                    entity.setUuid(camData.path("uuid").asText());
                    entity.setUrl(camData.path("main").asText());
                    entity.setSubUrl(camData.path("sub").asText());

                    String aiStatus = camData.path("status").asText();

                    BaseStatus mappedStatus = BaseStatus.INACTIVE;
                    if("live".equalsIgnoreCase(aiStatus)){
                        mappedStatus = BaseStatus.ACTIVE;
                    }
                    entity.setStatus(mappedStatus);

                    if (entity.getId() == null) {
                        entity.setType(BaseStatus.ACTIVE); // Mặc định khi tạo mới
                    }
                    syncedCameras.add(entity);
                }
                if (!syncedCameras.isEmpty()) {
                    syncedCameras = cameraRepository.saveAll(syncedCameras);
                }
            } else {
                log.warn("Cấu trúc JSON từ AI API không phải là Object. Không thể đồng bộ.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi parse dữ liệu AI API", e);
        }


        return cameraMapper.toResponseList(syncedCameras);
    }

    private Map<String, AICameraDetail> fetchAICamerasSafely() {
        try {
            return aiCameraService.getCameras();
        } catch (Exception e) {
            log.warn("Cannot reach AI service");
            return Map.of();
        }
    }
}