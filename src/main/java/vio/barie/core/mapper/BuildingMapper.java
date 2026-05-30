package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.BuildingCreateRequest;
import vio.barie.core.dto.BuildingResponse;
import vio.barie.core.entity.BuildingEntity;

import java.util.List;

@Component
public class BuildingMapper {

    public BuildingEntity toEntity(BuildingCreateRequest request) {
        BuildingEntity entity = new BuildingEntity();
        entity.setName(request.getName());
        entity.setAddress(request.getAddress());
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        return entity;
    }

    public BuildingResponse toResponse(BuildingEntity entity) {
        BuildingResponse response = new BuildingResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setAddress(entity.getAddress());
        response.setDescription(entity.getDescription());
        response.setStatus(entity.getStatus());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    public List<BuildingResponse> toResponseList(List<BuildingEntity> entities) {
        return entities.stream()
                .map(this::toResponse)
                .toList();
    }
}