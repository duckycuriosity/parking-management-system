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
        return entity;
    }

    public ParkingLotResponse toResponse(ParkingLotEntity entity) {
        ParkingLotResponse resp = new ParkingLotResponse();
        resp.setId(entity.getId());
        resp.setName(entity.getName());
        if (entity.getBuilding() != null)
            resp.setBuildingId(entity.getBuilding().getId());
        resp.setCreatedAt(entity.getCreatedAt());
        return resp;
    }

    public List<ParkingLotResponse> toResponseList(List<ParkingLotEntity> entities) {
        return entities.stream().map(this::toResponse).toList();
    }
}
