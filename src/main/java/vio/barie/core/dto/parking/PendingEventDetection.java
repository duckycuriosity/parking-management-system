package vio.barie.core.dto.parking;

import lombok.Data;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;

@Data
public class PendingEventDetection {
    String entryBarrierId;
    Long entryCameraId;
    Long entryLaneId;

    String exitBarrierId;
    Long exitCameraId;
    Long exitLaneId;

    DetectionType type; // FACE | PLATE
    DirectionType directionType; // ENTRY | EXIT
    Integer profileId;
    String plateNumber;
    String aiPlateId;
    long timestampMs;
}
