package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import org.springframework.stereotype.Service;
import vio.barie.core.entity.CameraEntity;
import vio.barie.core.mapper.CameraMapper;
import vio.barie.core.repository.CameraRepository;

import java.util.List;

@Service
public class CameraService {
    private final RestClient restClient;
    private final CameraRepository cameraRepository;
    private final CameraMapper cameraMapper;
    private final ObjectMapper objectMapper;

    public CameraService(
            @Qualifier("aiRestClient") RestClient restClient,
            CameraRepository cameraRepository,
            CameraMapper cameraMapper,
            ObjectMapper objectMapper

    ) {
        this.restClient = restClient;
        this.cameraRepository = cameraRepository;
        this.cameraMapper = cameraMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CameraResponse createCamera(CameraCreateRequest clientBody) {

        CameraEntity camera = new CameraEntity();
        String rawResponse = restClient.post()
                .uri("/Camera")
                .contentType(MediaType.APPLICATION_JSON)
                .body(clientBody)
                .retrieve()
                .body(String.class);

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("AI camera service returned empty response");
        }

        JsonNode aiCamera;
        try {
            aiCamera = objectMapper.readTree(rawResponse);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse AI camera response: " + rawResponse, ex);
        }

        camera.setAiCameraId(aiCamera.path("id").asLong());
        camera.setName(aiCamera.path("name").asText());
        camera.setUuid(aiCamera.path("uuid").asText());
        camera.setUrl(aiCamera.path("url").asText());
        String typeStr = aiCamera.path("type").asText();
        if (!typeStr.isBlank()) {
            try {
                camera.setType(BaseStatus.valueOf(typeStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid camera type from AI system: " + typeStr);
            }
        }
        String statusStr = aiCamera.path("status").asText();
        if (!statusStr.isBlank()) {
            try {
                camera.setStatus(BaseStatus.valueOf(statusStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                camera.setStatus(BaseStatus.ACTIVE);
            }
        } else {
            // Mặc định lúc tạo mới vào DB thường là ACTIVE
            camera.setStatus(BaseStatus.ACTIVE);
        }
        CameraEntity saved = cameraRepository.save(camera);

        return cameraMapper.toResponse(saved);
    }


    public List<CameraResponse> getCameraList() {
        List<CameraEntity> entities = cameraRepository.findAllWithRelations();
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
        if (request.getUrl() != null) {
            entity.setUrl(request.getUrl());
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

}