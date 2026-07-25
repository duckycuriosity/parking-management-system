package vio.barie.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.*;
import org.springframework.stereotype.Service;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.service.AIService.AICameraService;

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
        List<CameraEntity> entities = cameraRepository.searchCameras(formattedSearchKey, status);
        List<CameraResponse> responses = cameraMapper.toResponseList(entities);
        Map<String, AICameraDetail> aiCameras = fetchAICamerasSafely();
        for (CameraResponse res: responses) {
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
            }
        }

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

//    public StreamResponse startLiveStream(String cameraUUID) {
//        // Lấy thông tin Camera trong hệ thống
//        CameraEntity entity = cameraRepository.findCameraByUUID(cameraUUID)
//                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + cameraUUID));
//
//        //  Tạo Request gửi VMS bằng aiCameraId
//        VmsLiveStreamRequest vmsRequest = new VmsLiveStreamRequest();
//        vmsRequest.setCameraId(entity.getAiCameraId());
//        vmsRequest.setChannel(0);
//
//        //  Gọi sang VMS
//        VmsLiveStreamResponse vmsResponse = vmsTokenService.executeWithAutoLoginRetry(token ->
//                vmsRestClient.post()
//                        .uri(vmsApiUrl + "/LiveStream")
//                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
//                        .header(HttpHeaders.ACCEPT, "*/*")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .body(vmsRequest)
//                        .retrieve()
//                        .body(VmsLiveStreamResponse.class)
//        );
//
//        // dữ liệu trả về cho FE
//        StreamResponse response = new StreamResponse();
//        response.setVideoCodec(vmsResponse.getVideoCodec());
//        response.setAudioCodec(vmsResponse.getAudioCodec());
//        response.setVideoCodecTag(vmsResponse.getVideoCodecTag());
//
//        // chuẩn hóa link WebSocket tự động bằng properties
//        if (vmsResponse.getLink() != null) {
//            String normalizedLink = vmsResponse.getLink().replace(internalWsHost, publicWsHost);
//            response.setLink(normalizedLink);
//        }
//
//        return response;
//    }
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