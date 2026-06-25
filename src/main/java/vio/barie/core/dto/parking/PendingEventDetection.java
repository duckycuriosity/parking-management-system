package vio.barie.core.dto.parking;

import lombok.Data;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;

import java.util.ArrayList;
import java.util.List;

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

    private List<EventImageInfo> listImageInfo = new ArrayList<>();
}
