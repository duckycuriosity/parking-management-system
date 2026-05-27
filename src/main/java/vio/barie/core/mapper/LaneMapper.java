package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.LaneCreateRequest;
import vio.barie.core.dto.LaneResponse;
import vio.barie.core.entity.LaneEntity;

import java.util.List;

@Component
public class LaneMapper {

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
        if (entity.getParkingDevices() != null) {
            response.setParkingDeviceIds(entity.getParkingDevices().stream().map(d -> d.getId()).toList());
        }

        return response;
    }

    public List<LaneResponse> toResponseList(List<LaneEntity> entities) {
        return entities.stream().map(this::toResponse).toList();
    }
}