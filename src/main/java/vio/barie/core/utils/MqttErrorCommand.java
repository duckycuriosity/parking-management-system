package vio.barie.core.utils;

import lombok.Data;

@Data
public class MqttErrorCommand {
    private String message;
    private String cameraUUID;
    private DetectionType type;
    private String barrierId;
}
