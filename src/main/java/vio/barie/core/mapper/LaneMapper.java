package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.LaneCreateRequest;
import vio.barie.core.dto.LaneResponse;
import vio.barie.core.dto.camera.CameraStreamResponse;
import vio.barie.core.entity.LaneEntity;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class LaneMapper {
    private static final String STREAM_BASE_URL = "/api/camera/stream/";

    public LaneEntity toEntity(LaneCreateRequest request) {
        LaneEntity entity = new LaneEntity();
        entity.setName(request.getName());
        entity.setType(request.getType());
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        return entity;
    }

    public LaneResponse toResponse(LaneEntity entity) {
        LaneResponse response = new LaneResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setType(entity.getType());
        response.setDescription(entity.getDescription());
        response.setStatus(entity.getStatus());
        response.setCreatedAt(entity.getCreatedAt());
        if (entity.getParkingLot() != null) {
            response.setParkingLotId(entity.getParkingLot().getId());
            response.setParkingLotName(entity.getParkingLot().getName());
        }
        if (entity.getCameras() != null) {
            response.setCameraIds(entity.getCameras().stream().map(c -> c.getId()).toList());
        }
        if (entity.getParkingDevices() != null && !entity.getParkingDevices().isEmpty()) {
            response.setParkingDeviceIds(entity.getParkingDevices().get(0).getId());
        }

        return response;
    }

    public List<LaneResponse> toResponseList(List<LaneEntity> entities) {
        return entities.stream().map(this::toResponse).toList();
    }

    public List<CameraStreamResponse> toCameraStreamResponseList(List<vio.barie.core.entity.CameraEntity> cameras) {
        if (cameras == null || cameras.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        return cameras.stream().map(cam -> {
            CameraStreamResponse response = new CameraStreamResponse();
            response.setId(cam.getId());
            response.setAiCameraId(cam.getAiCameraId());
            if (cam.getAiCameraId() != null && !cam.getAiCameraId().isBlank()) {
                response.setStreamUrl(STREAM_BASE_URL + cam.getAiCameraId().trim());
            }
            return response;
        }).collect(Collectors.toList());
    }

}