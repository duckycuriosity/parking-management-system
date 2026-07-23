package vio.barie.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.*;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.camera.CameraUpdateRequest;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CameraService {

    private final RestClient aiRestClient;
    private final RestClient vmsRestClient;
    private final CameraRepository cameraRepository;
    private final CameraMapper cameraMapper;
    private final ObjectMapper objectMapper;

    private final VmsTokenService vmsTokenService;
    @Value("${ai.core.base-url}")
    private String aiApiUrl;

    @Value("${vms.core.base-url}")
    private String vmsApiUrl;

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
            @Qualifier("vmsRestClient") RestClient vmsRestClient,
            CameraRepository cameraRepository,
            CameraMapper cameraMapper,
            ObjectMapper objectMapper,
            VmsTokenService vmsTokenService

    ) {
        this.aiRestClient = aiRestClient;
        this.vmsRestClient = vmsRestClient;
        this.cameraRepository = cameraRepository;
        this.cameraMapper = cameraMapper;
        this.objectMapper = objectMapper;
        this.vmsTokenService = vmsTokenService;
    }

    @Transactional
    public CameraResponse createCamera(CameraCreateRequest clientBody) {

        CameraEntity camera = new CameraEntity();
        Map<String, Object> aiPayload = new java.util.HashMap<>();

        aiPayload.put("name", clientBody.getName());
        aiPayload.put("url", clientBody.getMain() != null ? clientBody.getMain().trim() : "");
        aiPayload.put("mode", clientBody.getMode());
        aiPayload.put("protocol", clientBody.getProtocol());


        String responseBody = aiRestClient.post()
                .uri(aiApiUrl + "/add_stream")
                .header(HttpHeaders.ACCEPT, "*/*")
                .contentType(MediaType.APPLICATION_JSON)
                .body(aiPayload)
                .retrieve()
                .body(String.class);
        JsonNode aiResponse = safeParseJson(responseBody);

        camera.setName(clientBody.getName());
        camera.setUrl(clientBody.getMain() != null ? clientBody.getMain().trim() : "");


        if (aiResponse.isObject() && !aiResponse.isEmpty()) {
            if (aiResponse.has("cam_id")) {
                camera.setAiCameraId(aiResponse.path("cam_id").asText());
            } else if (aiResponse.has("id")) {
                camera.setAiCameraId(aiResponse.path("id").asText());
            }

            if (aiResponse.has("uuid")) {
                camera.setUuid(aiResponse.path("uuid").asText());
            }
        }
        camera.setLatitude(clientBody.getLatitude() != null ? clientBody.getLatitude() : 0.0);
        camera.setLongitude(clientBody.getLongitude() != null ? clientBody.getLongitude() : 0.0);
        camera.setSubUrl(clientBody.getSub());
        camera.setMode(clientBody.getMode());
        camera.setProtocol(clientBody.getProtocol());
        camera.setType(BaseStatus.ACTIVE);
        camera.setStatus(BaseStatus.ACTIVE);

        CameraEntity saved = cameraRepository.save(camera);
        return cameraMapper.toResponse(saved);
    }


    public List<CameraResponse> getCameraList(String searchKey, BaseStatus status) {
        String formattedSearchKey = null;
        if (searchKey != null && !searchKey.trim().isEmpty()) {
            formattedSearchKey = "%" + searchKey.trim() + "%";
        }
        List<CameraEntity> entities = cameraRepository.searchCameras(formattedSearchKey, status);

        return cameraMapper.toResponseList(entities);
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

        Map<String, Object> aiPayload = new java.util.HashMap<>();


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
            entity.setMode(request.getMode());
        }
        if (request.getProtocol() != null) {
            entity.setProtocol(request.getProtocol());
        }

        String aiCameraId = entity.getAiCameraId();
        if (aiCameraId != null && !aiCameraId.isBlank()) {
            aiPayload.put("name", entity.getName());
            aiPayload.put("url", entity.getUrl());
            aiPayload.put("mode", entity.getMode());
            aiPayload.put("protocol", entity.getProtocol());

            try {
                String responseBody = aiRestClient.post()
                        .uri(aiApiUrl + "/update_stream/" + aiCameraId)
                        .header(HttpHeaders.ACCEPT, "*/*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(aiPayload)
                        .retrieve()
                        .body(String.class);


            } catch (Exception e) {
                throw new RuntimeException("Không thể đồng bộ cập nhật với AI API: " + e.getMessage(), e);
            }

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
            try {
                aiRestClient.delete()

                        .uri(aiApiUrl + "/delete_stream?cam_id=" + aiCameraId)
                        .header(HttpHeaders.ACCEPT, "*/*")

                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception e) {
                throw new RuntimeException("Không thể đồng bộ xóa Camera với hệ thống VMS: " + e.getMessage(), e);
            }
            cameraRepository.deleteById(id);
        }
    }

    public StreamResponse startLiveStream(String cameraUUID) {
        // Lấy thông tin Camera trong hệ thống
        CameraEntity entity = cameraRepository.findCameraByUUID(cameraUUID)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + cameraUUID));

        //  Tạo Request gửi VMS bằng aiCameraId
        VmsLiveStreamRequest vmsRequest = new VmsLiveStreamRequest();
        vmsRequest.setCameraId(entity.getAiCameraId());
        vmsRequest.setChannel(0);

        //  Gọi sang VMS
        VmsLiveStreamResponse vmsResponse = vmsTokenService.executeWithAutoLoginRetry(token ->
                vmsRestClient.post()
                        .uri(vmsApiUrl + "/LiveStream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ACCEPT, "*/*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(vmsRequest)
                        .retrieve()
                        .body(VmsLiveStreamResponse.class)
        );

        // dữ liệu trả về cho FE
        StreamResponse response = new StreamResponse();
        response.setVideoCodec(vmsResponse.getVideoCodec());
        response.setAudioCodec(vmsResponse.getAudioCodec());
        response.setVideoCodecTag(vmsResponse.getVideoCodecTag());

        // chuẩn hóa link WebSocket tự động bằng properties
        if (vmsResponse.getLink() != null) {
            String normalizedLink = vmsResponse.getLink().replace(internalWsHost, publicWsHost);
            response.setLink(normalizedLink);
        }

        return response;
    }

    public ReportLinkResponse getReportLink(Integer identityId) {
        ReportLinkResponse response = vmsTokenService.executeWithAutoLoginRetry(token ->
                vmsRestClient.get()
                        .uri(vmsApiUrl + "/ReportLink/" + identityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(ReportLinkResponse.class)
        );

        // chuẩn hóa link WebSocket tự động bằng properties
        if (response != null && response.getLink() != null) {
            String normalizedLink = response.getLink().replace(internalWsHost, publicWsHost);
            response.setLink(normalizedLink);
        }

        return response;
    }

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

    private JsonNode safeParseJson(String jsonString) {
        // Kiểm tra null/empty
        if (jsonString == null || jsonString.isBlank()) {
            log.error("❌ Response body rỗng!");
            throw new RuntimeException("Phản hồi từ VMS API bị rỗng");
        }

        try {
            JsonNode node = objectMapper.readTree(jsonString);

            // Kiểm tra kết quả parse
            if (node == null || node.isMissingNode() || node.isNull()) {
                log.error("❌ Parse thành công nhưng kết quả null. Input: {}", jsonString);
                throw new RuntimeException("Kết quả parse JSON là null");
            }

            log.debug("✅ Parse JSON thành công: {}", node.toString());
            return node;

        } catch (JsonMappingException e) {
            // JSON hợp lệ cú pháp nhưng không map được
            log.error("❌ JsonMappingException - Có thể response là array thay vì object");
            log.error("   Nguyên nhân: {}", e.getOriginalMessage());
            log.error("   Path lỗi: {}", e.getPath());
            throw new RuntimeException("Cấu trúc JSON không khớp: " + e.getOriginalMessage(), e);

        } catch (JsonProcessingException e) {
            // JSON bị lỗi cú pháp
            log.error("❌ JsonProcessingException - JSON syntax error");
            log.error("   Nguyên nhân: {}", e.getMessage());
            log.error("   Vị trí lỗi: line {}, offset {}",
                    e.getLocation() != null ? e.getLocation().getLineNr() : "N/A",
                    e.getLocation() != null ? e.getLocation().getColumnNr() : "N/A");
            log.error("   Raw response (500 ký tự đầu): {}",
                    jsonString.length() > 500 ? jsonString.substring(0, 500) + "..." : jsonString);
            throw new RuntimeException("JSON không hợp lệ: " + e.getMessage(), e);
        }
    }
}