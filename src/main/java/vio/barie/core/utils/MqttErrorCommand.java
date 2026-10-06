package vio.barie.core.utils;

import lombok.Data;
import vio.barie.core.dto.LaneEventImage;

import java.util.List;

@Data
public class MqttErrorCommand {
    private String message;
    private String cameraUUID;
    private DetectionType type;
    private String barrierId;
    private String plateNumber;
    private Integer profileId;
    private String code;
    private Long incidentId;
    private List<LaneEventImage> images;
}
