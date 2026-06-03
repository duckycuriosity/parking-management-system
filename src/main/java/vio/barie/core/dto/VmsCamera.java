package vio.barie.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class VmsCamera {
    private Long id; // tương ứng với aiCameraId trong DB của bạn
    private String name;
    private String main;
    private String sub;
    private Boolean enableRecord;
    private JsonNode recordSchedule;
    private JsonNode extra; // Chứa uuid, location...
    private Long vmsId;
    private Long cameraId;
}
