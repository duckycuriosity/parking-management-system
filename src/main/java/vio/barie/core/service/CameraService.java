package vio.barie.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.*;
import org.springframework.stereotype.Service;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.service.AIService.AICameraService;

import java.io.OutputStream;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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

    // nội bộ
    @Value("${vms.ws.internal-ws-host}")
    private String internalWsHost;
    //  public
    @Value("${vms.ws.public-ws-host}")
    private String publicWsHost;

    @Value("${vms.core.cookie}")
    private String vmsCookie;

    @Value("${vms.core.origin}")
    private String vmsOrigin;

    @Value("${vms.core.user-agent}")
    private String vmsUserAgent;

    @Value("${vms.core.login-referer}")
    private String vmsReferer;

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

    @Transactional
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
        String formattedSearchKey = null;
        if (searchKey != null && !searchKey.trim().isEmpty()) {
            formattedSearchKey = "%" + searchKey.trim() + "%";
        }
        List<CameraEntity> entities = cameraRepository.searchCameras(formattedSearchKey, null);
        log.info("Tìm thấy camera {} trong db", entities.size());
        Map<String, AICameraDetail> aiCameras = fetchAICamerasSafely();
        log.info("Gọi AI serverice check xem Số lượng camera AI trả về: {}", aiCameras.size());
        log.info("Danh sách các camera aiCamera {}", aiCameras.keySet());
        boolean isDbUpdated = false;
        log.info("[Bước 3] Bắt đầu đối chiếu ID và Trạng thái...");
        for(CameraEntity entity:entities){
            AICameraDetail detail = aiCameras.get(entity.getAiCameraId());
            log.info("  -> Đang xét Camera DB [ID: {}], aiCameraId lưu trên DB: '{}'", entity.getId(),detail);
            BaseStatus realStatus = entity.getStatus();
            if (detail != null) {
                String aiStatus = detail.getStatus();
                log.info("     + TÌM THẤY trên AI. Trạng thái AI trả về: '{}'", aiStatus);
                if ("live".equalsIgnoreCase(aiStatus)) {
                    realStatus = BaseStatus.ACTIVE;
                } else if ("error".equalsIgnoreCase(aiStatus)) {
                    realStatus = BaseStatus.INACTIVE;
                }else {
                    log.warn("     + CẢNH BÁO: AI trả về trạng thái lạ '{}', giữ nguyên trạng thái cũ.", aiStatus);
                }
            } else {
                log.warn("     + LỖI MAP: KHÔNG tìm thấy aiCameraId '{}' trong kết quả của AI -> Bị ép về INACTIVE", detail);
                realStatus = BaseStatus.INACTIVE;
            }
            if (entity.getStatus() != realStatus) {
                log.info("     + >>> UPDATE DB: Đổi trạng thái từ {} sang {}", entity.getStatus(), realStatus);
                entity.setStatus(realStatus);
                isDbUpdated = true;
            }
        }
        if (isDbUpdated) {
            log.info("[Bước 4] Có sự thay đổi trạng thái, đang lưu xuống Database...");
            cameraRepository.saveAll(entities);
        }else {
            log.info("[Bước 4] Không có thay đổi nào, bỏ qua lệnh saveAll.");
        }
        List<CameraResponse> responses = cameraMapper.toResponseList(entities);
        log.info("[Bước 5] Đã map xong entity sang DTO response.");
        for (CameraResponse res : responses) {
            AICameraDetail detail = aiCameras.get(res.getAiCameraId());
            if (detail != null) {
                res.setAiStreamStatus(detail.getStatus());
                res.setAiStreamError(detail.getError());
                res.setFps(detail.getFps());
                if (detail.getLiveStream() != null) {
                    res.setLiveStreamLink(detail.getLiveStream().getLink());
                    res.setAudioCodec(detail.getLiveStream().getAudioCodec());
                    res.setVideoCodec(detail.getLiveStream().getVideoCodec());
                }

            } else {
                res.setAiStreamStatus("unknown");
            }
        }
        if (status != null) {
            log.info("[Bước 6] User filter status = {}. Bắt đầu lọc...", status);
            responses = responses.stream()
                    .filter(res -> res.getStatus() == status)
                    .toList();
        }
        log.info("========== KẾT THÚC GET CAMERA LIST. Trả về {} camera ==========", responses.size());
        return responses;
    }

    public CameraResponse getCameraById(Long id) {
        CameraEntity entity = cameraRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + id));
        return cameraMapper.toResponse(entity);
    }

    @Transactional
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

    public void aiVideoStream(String aiCameraId, HttpServletResponse response) {

        cameraRepository.findCameraByAiCameraId(aiCameraId)
                .orElseThrow(() -> new RuntimeException("Camera không tồn tại với ID AI: " + aiCameraId));

        String cleanId = aiCameraId.replaceAll("[\\r\\n\\t ]", "");
        String streamUrl = streamAI + "/video_feed/" + cleanId;
        log.info("[STREAM] URL gọi sang AI: {}", streamUrl);

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
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            String aiContentType = aiResponse.headers().firstValue("Content-Type")
                    .orElse("multipart/x-mixed-replace; boundary=frame");
            log.info("[STREAM] Đã nhận Content-Type từ AI: {}", aiContentType);

            response.setContentType(aiContentType);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
            response.setHeader("Connection", "keep-alive");
            response.setBufferSize(0); // tắt bo dem

            try (InputStream is = aiResponse.body();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = is.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                }
            } catch (Exception e) {
                log.warn("[STREAM] Ngắt kết nối stream (User đóng trình duyệt)");
            }

        } catch (Exception e) {
            log.error("[STREAM] Lỗi luồng proxy: ", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
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

                    if (entity.getId() == null) {
                        entity.setStatus(BaseStatus.ACTIVE);
                        entity.setType(BaseStatus.ACTIVE); // Mặc định khi tạo mới
                    }

                    CameraEntity savedEntity = cameraRepository.save(entity);
                    syncedCameras.add(savedEntity);
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