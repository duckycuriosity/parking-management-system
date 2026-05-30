package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.CameraCreateRequest;
import vio.barie.core.dto.camera.CameraResponse;
import vio.barie.core.entity.CameraEntity;

import java.util.List;

@Component
public class CameraMapper {
    public CameraEntity toEntity(CameraCreateRequest request) {
        CameraEntity entity = new CameraEntity();
        entity.setName(request.getName());
        if (request.getType() != null && !request.getType().isBlank()) {
            try {
                entity.setType(BaseStatus.valueOf(request.getType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid type: " + request.getType());
            }
        }
        entity.setUrl(request.getUrl());
        return entity;
    }

    public CameraResponse toResponse(CameraEntity entity) {
        CameraResponse response = new CameraResponse();
        response.setId(entity.getId());
        response.setUuid(entity.getUuid());
        response.setType(entity.getType());
        response.setStatus(entity.getStatus());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setUrl(entity.getUrl());
        response.setAiCameraId(entity.getAiCameraId());

        if (entity.getLane() != null) {
            response.setLane_id(entity.getLane().getId());
            response.setLaneName(entity.getLane().getName());
            if (entity.getLane().getParkingLot() != null) {
                response.setParkingLotName(entity.getLane().getParkingLot().getName());
            }
        }

        return response;
    }

    public List<CameraResponse> toResponseList(List<CameraEntity> entities) {
        return entities.stream()
                .map(this::toResponse)
                .toList();
    }
}
