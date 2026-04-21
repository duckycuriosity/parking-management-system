package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.entity.DeviceEntity;
@Component
public class PlateMapper {
    public DeviceEntity toDevice(AiPlateResponse ai){
        DeviceEntity device = new DeviceEntity();
        device.setPlateNumber(ai.getPlate());
        device.setAiIdentityPlateId(ai.getUuid());
        device.setInfo("""
                {
                 "aiId": %d,
                                  "type": "%s",
                                  "enabled": %s
                }
                """.formatted(ai.getId(), ai.getType(), ai.getEnabled()));
        return device;


    }
}
