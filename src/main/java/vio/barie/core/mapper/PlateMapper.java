package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.entity.VehicleEntity;

@Component
public class PlateMapper {
    public VehicleEntity toVehicle(AiPlateResponse ai) {
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setPlateNumber(ai.getPlate());
        vehicle.setAiIdentityPlateId(ai.getUuid());
        vehicle.setVehicleType(ai.getType());
        vehicle.setInfo("""
                {
                 "aiId": %d,
                                  "type": "%s",
                                  "enabled": %s
                }
                """.formatted(ai.getId(), ai.getType(), ai.getEnabled()));
        return vehicle;
    }
}