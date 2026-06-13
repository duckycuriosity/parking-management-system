package vio.barie.core.dto.camera;

import cn.hutool.core.date.DateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;
import vio.barie.core.common.BaseStatus;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CameraResponse {
    private Long id;
    private String plateNumber;
    private String vehicleType;
    private Long aiCameraId;
    private String name;
    private String url;
    private BaseStatus type;
    private String uuid;
    private JsonNode extra;
    private Long lane_id;
    private String parkingLotName;
    private String laneName;
    private String description;
    private BaseStatus status;
    private DateTime created_date;
    private double latitude;
    private double longitude;
    private String sub_url;
}
