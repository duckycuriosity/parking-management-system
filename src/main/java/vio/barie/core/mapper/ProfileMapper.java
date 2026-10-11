package vio.barie.core.mapper;

import org.springframework.stereotype.Component;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.entity.ParkingCustomerEntity;
@Component
public class ProfileMapper {
    public ParkingCustomerEntity toEntity(AiProfileResponse ai){
        ParkingCustomerEntity entity =  new ParkingCustomerEntity();
        entity.setAiIdentityId(ai.getUuid());
        entity.setUsername(ai.getName());
        entity.setZaloId("");
        entity.setAiProfileId(ai.getId());
        return entity;
    }
}
