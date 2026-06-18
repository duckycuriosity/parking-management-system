package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.ParkingLotCreateRequest;
import vio.barie.core.dto.ParkingLotResponse;
import vio.barie.core.entity.ParkingLotEntity;

import java.util.List;

@Component
public class ParkingLotMapper {

    public ParkingLotEntity toEntity(ParkingLotCreateRequest request) {
        ParkingLotEntity entity = new ParkingLotEntity();

        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        entity.setMaxMotorcycleCapacity(request.getMaxMotorcycleCapacity());
        entity.setMaxCarCapacity(request.getMaxCarCapacity());
        return entity;
    }

    public ParkingLotResponse toResponse(ParkingLotEntity entity) {
        ParkingLotResponse resp = new ParkingLotResponse();
        resp.setId(entity.getId());
        resp.setName(entity.getName());
        if (entity.getBuilding() != null) {
            resp.setBuildingId(entity.getBuilding().getId());
            resp.setBuildingName(entity.getBuilding().getName());
        }
        resp.setDescription(entity.getDescription());
        resp.setStatus(entity.getStatus());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setMaxMotorcycleCapacity(entity.getMaxMotorcycleCapacity());
        resp.setMaxCarCapacity(entity.getMaxCarCapacity());
        return resp;
    }

    public List<ParkingLotResponse> toResponseList(List<ParkingLotEntity> entities) {
        return entities.stream().map(this::toResponse).toList();
    }
}
