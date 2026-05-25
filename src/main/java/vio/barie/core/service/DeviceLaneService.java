package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.dto.DeviceLaneResponse;
import vio.barie.core.repository.DeviceLaneRepository;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.utils.DetectionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceLaneService {
    private final ParkingDeviceRepository parkingDeviceRepository;

    public List<DeviceLaneResponse> getAllDeviceByLaneId(Long laneId) {
        List<DeviceLaneRepository> rawData = parkingDeviceRepository.findAllDeviceDetailsByLaneId(laneId);

        // Gom nhóm theo device id
        Map<Long, List<DeviceLaneRepository>> groupedByDevice = rawData.stream()
                .filter(row -> row.getDeviceId() != null)
                .collect(Collectors.groupingBy(DeviceLaneRepository::getDeviceId));

        List<DeviceLaneResponse> responseList = new ArrayList<>();

        for (Map.Entry<Long, List<DeviceLaneRepository>> deviceEntry : groupedByDevice.entrySet()) {
            List<DeviceLaneRepository> deviceRows = deviceEntry.getValue();
            DeviceLaneRepository firstDeviceRow = deviceRows.get(0);

            // Gom nhóm tiếp theo Barrier id trong device đó
            Map<String, List<DeviceLaneRepository>> groupedByBarrier = deviceRows.stream()
                    .filter(row -> row.getBarrierId() != null)
                    .collect(Collectors.groupingBy(DeviceLaneRepository::getBarrierId));

            List<DeviceLaneResponse.BarrierInfo> barrierInfos = new ArrayList<>();
            for (Map.Entry<String, List<DeviceLaneRepository>> barrierEntry : groupedByBarrier.entrySet()) {
                List<DeviceLaneRepository> barrierRows = barrierEntry.getValue();
                DeviceLaneRepository firstBarrierRow = barrierRows.get(0);


                List<DeviceLaneResponse.CameraInfo> cameraInfos = barrierRows.stream()
                        .filter(row -> row.getCameraId() != null)
                        .map(row -> DeviceLaneResponse.CameraInfo.builder()
                                .id(row.getCameraId())
                                .name(row.getCameraName())
                                .url(row.getCameraUrl())
                                .type(parseEnum(BaseStatus.class, row.getCameraType()))
                                .role(parseEnum(DetectionType.class, row.getCameraRole()))
                                .build())
                        .distinct()
                        .collect(Collectors.toList());

                barrierInfos.add(DeviceLaneResponse.BarrierInfo.builder()
                        .id(firstBarrierRow.getBarrierLocalId())
                        .barrierId(firstBarrierRow.getBarrierId())
                        .description(firstBarrierRow.getBarrierDescription())
                        .direction(firstBarrierRow.getDirection())
                        .cameras(cameraInfos)
                        .build());
            }

            responseList.add(DeviceLaneResponse.builder()
                    .id(firstDeviceRow.getDeviceId())
                    .deviceId(firstDeviceRow.getDeviceCode())
                    .status(parseEnum(OperatingStatus.class, firstDeviceRow.getDeviceStatus()))
                    .firmware_version(firstDeviceRow.getFirmwareVersion())
                    .barriers(barrierInfos)
                    .build());
        }

        return responseList;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
        return (value == null || value.trim().isEmpty()) ? null : Enum.valueOf(enumClass, value);
    }
}
