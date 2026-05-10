package vio.barie.core.dto.camera;

import cn.hutool.core.date.DateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CameraResponse {
    private Long aiCameraId;
    private String name;
    private String url;
    private String type;
    private String uuid;
    private JsonNode extra;
    private Long lane_id;
    private String status;
    private DateTime created_date;
}
