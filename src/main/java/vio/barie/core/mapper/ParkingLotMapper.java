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
        if (request.getVerificationMode() != null) {
            entity.setVerificationMode(request.getVerificationMode());
        }
        if(request.getOperatingMode() !=null){
            entity.setOperatingMode(request.getOperatingMode());
        }
        return entity;
    }

    public ParkingLotResponse toResponse(ParkingLotEntity entity) {
        ParkingLotResponse resp = new ParkingLotResponse();
        resp.setId(entity.getId());
        resp.setParkingLotCode(
                String.format("PL-%02d", entity.getId())
        );

        resp.setName(entity.getName());
        if (entity.getBuilding() != null) {
            resp.setBuildingId(entity.getBuilding().getId());
            resp.setBuildingName(entity.getBuilding().getName());
        }
        resp.setOperatingMode(entity.getOperatingMode());
        resp.setDescription(entity.getDescription());
        resp.setStatus(entity.getStatus());
        resp.setCreatedAt(entity.getCreatedAt());
        resp.setMaxMotorcycleCapacity(entity.getMaxMotorcycleCapacity());
        resp.setMaxCarCapacity(entity.getMaxCarCapacity());
        resp.setVerificationMode(entity.getVerificationMode());
        return resp;
    }

    public List<ParkingLotResponse> toResponseList(List<ParkingLotEntity> entities) {
        return entities.stream().map(this::toResponse).toList();
    }
}
