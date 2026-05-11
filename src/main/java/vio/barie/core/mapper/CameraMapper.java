package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import vio.barie.core.entity.CameraEntity;

import java.util.List;

@Component
public class CameraMapper {
    public CameraEntity toEntity (CameraCreateRequest request) {
        CameraEntity entity = new CameraEntity();
        entity.setAiCameraId((long)request.getId());
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setUrl(request.getUrl());
        return entity;
    }

    public CameraResponse toResponse (CameraEntity entity) {
        CameraResponse response = new CameraResponse();
        response.setUuid(entity.getUuid());
        response.setType(entity.getType());
        response.setName(entity.getName());
        response.setUrl(entity.getUrl());
        response.setAiCameraId(entity.getAiCameraId());
        return response;
    }

    public List<CameraResponse> toResponseList (List<CameraEntity> entities) {
        return entities.stream()
                .map(this::toResponse)
                .toList();
    }
}
