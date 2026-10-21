package vio.barie.core.dto;

import lombok.Data;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.dto.camera.CameraStreamResponse;
import vio.barie.core.utils.DirectionType;

import java.util.List;

@Data
public class LaneResponse {
    private Long id;
    private String name;
    private String parkingLotName;
    private DirectionType type;
    private String description;
    private BaseStatus status;
    private Long parkingLotId;
    private java.time.LocalDateTime createdAt;
    private Long cameraFaceId;
    private Long cameraPlateId;
    private String parkingDeviceCode;
    private String laneCode;
   
}