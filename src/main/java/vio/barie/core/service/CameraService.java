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
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CameraService {

//    private final RestClient aiRestClient;
    private final RestClient vmsRestClient;
    private final CameraRepository cameraRepository;
    private final CameraMapper cameraMapper;
    private final ObjectMapper objectMapper;

    private final VmsTokenService vmsTokenService;
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
//        this.aiRestClient = aiRestClient;
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
        Map<String, Object> extra = new java.util.HashMap<>();
        Map<String, Object> location = new java.util.HashMap<>();

        location.put("latitude", clientBody.getLatitude());
        location.put("longitude", clientBody.getLongitude());

        extra.put("location", location);
        extra.put("uuid", UUID.randomUUID());

        aiPayload.put("name", clientBody.getName());
        aiPayload.put("main", clientBody.getMain() != null ? clientBody.getMain().trim() : "");
        aiPayload.put("sub", clientBody.getSub() != null ? clientBody.getSub().trim() : "");
        aiPayload.put("enableRecord", clientBody.isEnableRecord());
        aiPayload.put("extra", extra);
        aiPayload.put("id", 0);
        aiPayload.put("vmsId", 0);
        aiPayload.put("cameraId", 0);

        String responseBody = vmsTokenService.executeWithAutoLoginRetry(token ->
                vmsRestClient.post()
                        .uri(vmsApiUrl + "/Camera")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ACCEPT, "*/*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(aiPayload)
                        .retrieve()
                        .body(String.class)  // 👈 Nhận về String trước
        );

        JsonNode vmsResponse = safeParseJson(responseBody);
        JsonNode extraResponse = vmsResponse.path("extra");
        JsonNode locationResponse = extraResponse.path("location");

        camera.setAiCameraId(vmsResponse.path("id").asLong());
        camera.setName(vmsResponse.path("name").asText());
        camera.setUuid(extraResponse.path("uuid").asText()); // Lấy UUID do AI sinh ra
        camera.setUrl(vmsResponse.path("main").asText());
        camera.setLatitude(locationResponse.path("latitude").asDouble());
        camera.setLongitude(locationResponse.path("longitude").asDouble());
        camera.setType(BaseStatus.ACTIVE);
        camera.setStatus(BaseStatus.ACTIVE);

        CameraEntity saved = cameraRepository.save(camera);
        return cameraMapper.toResponse(saved);
    }


    public List<CameraResponse> getCameraList(String searchKey) {
        List<CameraEntity> entities;
        if (searchKey != null && !searchKey.trim().isEmpty()) {
            entities = cameraRepository.searchByParkingLotNameWithRelations(searchKey.trim());
        } else {
            entities = cameraRepository.findAllWithRelations();
        }
        return cameraMapper.toResponseList(entities);
    }

    public CameraResponse getCameraById(Long id) {
        CameraEntity entity = cameraRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + id));
        return cameraMapper.toResponse(entity);
    }

    @Transactional
    public CameraResponse updateCamera(Long id, CameraCreateRequest request) {
        CameraEntity entity = cameraRepository.findByIdWithRelations(id)
                .orElseThrow(() -> new RuntimeException("Camera not found with ID: " + id));

        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getMain() != null) {
            entity.setUrl(request.getMain());
        }

        CameraEntity updated = cameraRepository.save(entity);
        return cameraMapper.toResponse(updated);
    }

    @Transactional
    public void deleteCamera(Long id) {
        if (!cameraRepository.existsById(id)) {
            throw new RuntimeException("Camera not found with ID: " + id);
        }
        cameraRepository.deleteById(id);
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
    public List<CameraResponse> syncCamerasFromVms() { // 1. Đổi kiểu trả về
        log.info("bắt đầu chạy");
        String rawResponse = vmsTokenService.executeWithAutoLoginRetry(token -> {
            String combinedCookie = vmsCookie;
            String dynamicCookie = vmsTokenService.getDynamicCookie();
            if (dynamicCookie != null && !dynamicCookie.isEmpty()) {
                combinedCookie = combinedCookie + "; " + dynamicCookie;
            }
            log.info("1. Đang gửi request sang VMS với Cookie: {}", combinedCookie);
            return vmsRestClient.get()
                    .uri(vmsApiUrl + "/Camera")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.COOKIE, combinedCookie)
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .retrieve()
                    .body(String.class);
        });
        log.info(". DỮ LIỆU THÔ NHẬN ĐƯỢC TỪ VMS: {}", rawResponse);

        // 2. Khởi tạo list để chứa các camera đã lưu
        java.util.List<CameraEntity> syncedCameras = new java.util.ArrayList<>();

        if (rawResponse == null || rawResponse.isBlank()) {
            return cameraMapper.toResponseList(syncedCameras);
        }

        try {
            JsonNode vmsCamerasNode = objectMapper.readTree(rawResponse);
            log.info("3. Cấu trúc JSON cấp 1: isArray={}, isObject={}", vmsCamerasNode.isArray(), vmsCamerasNode.isObject());
            if (vmsCamerasNode.isArray()) {
                for (JsonNode vmsCam : vmsCamerasNode) {

                    Long aiCameraId = vmsCam.path("id").asLong();
                    if (aiCameraId == 0) continue;

                    CameraEntity entity = cameraRepository.findCameraByAiCameraId(aiCameraId)
                            .orElseGet(CameraEntity::new);

                    entity.setAiCameraId(aiCameraId);
                    entity.setName(vmsCam.path("name").asText());

                    String mainUrl = vmsCam.path("main").asText();
                    String subUrl = vmsCam.path("sub").asText();
                    String url = (mainUrl != null && !mainUrl.isBlank()) ? mainUrl : subUrl;
                    entity.setUrl(url);

                    JsonNode extra = vmsCam.path("extra");
                    if (!extra.isMissingNode() && extra.hasNonNull("uuid")) {
                        entity.setUuid(extra.path("uuid").asText());
                    }

                    if (entity.getId() == null) {
                        entity.setStatus(BaseStatus.ACTIVE);
                    }


                    CameraEntity savedEntity = cameraRepository.save(entity);
                    syncedCameras.add(savedEntity);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi parse cấu trúc JSON danh sách Camera từ VMS", e);
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