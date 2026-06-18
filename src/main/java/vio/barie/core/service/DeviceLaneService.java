package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import vio.barie.core.common.BaseStatus;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.dto.DeviceLaneResponse;
import vio.barie.core.dto.DeviceStatsResponse;
import vio.barie.core.dto.Result;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.CameraRepository;
import vio.barie.core.repository.DeviceLaneRepository;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.utils.DetectionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceLaneService {
    private final ParkingDeviceRepository parkingDeviceRepository;
    private final CameraRepository cameraRepository;
    private final BarrierRepository barrierRepository;

    public Result<List<DeviceLaneResponse>> getAllDeviceByLaneId(Long laneId) {
        List<DeviceLaneRepository> rawData = parkingDeviceRepository.findAllDeviceDetailsByLaneId(laneId);

        // check nếu hoàn toàn không có dữ liệu trả về từ query
        if (rawData.isEmpty()) {
            Optional<String> laneNameOpt = parkingDeviceRepository.findLaneNameById(laneId);
            if (laneNameOpt.isPresent()) {
                // trường hợp: Lane có tồn tại nhưng CHƯA gắn thiết bị nào
                String msg = String.format("Lane [%s] parking devices not configured.", laneNameOpt.get());
                log.warn("[DEVICE-LANE] {}", msg);
                return Result.ok(msg, new ArrayList<>()); // Bắn message này về cho FE
            } else {
                // Trường hợp: Nhập sai ID, Lane KHÔNG tồn tại
                String msg = String.format("The lane with ID %d does not exist in the system.", laneId);
                log.error("[DEVICE-LANE] {}", msg);
                return Result.ok(msg, new ArrayList<>());
            }
        }

        DeviceLaneRepository firstDeviceRow = rawData.get(0);

        // trường hợp LEFT JOIN ra dòng nhưng id thiết bị null (đề phòng)
        if (firstDeviceRow.getDeviceId() == null) {
            String msg = "This lane is not yet connected to any parking device.";
            log.warn("[DEVICE-LANE] Lane ID: {} {}", laneId, msg);
            return Result.ok(msg, new ArrayList<>());
        }
        // Gom nhóm theo device id
        Map<String, List<DeviceLaneRepository>> groupedByBarrier = rawData.stream()
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
                            .uuid(row.getUuid())
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

        DeviceLaneResponse response = DeviceLaneResponse.builder()
                .id(firstDeviceRow.getDeviceId())
                .deviceId(firstDeviceRow.getDeviceCode())
                .status(parseEnum(OperatingStatus.class, firstDeviceRow.getDeviceStatus()))
                .firmwareVersion(firstDeviceRow.getFirmwareVersion())
                .laneId(firstDeviceRow.getLaneId())
                .typeLane(firstDeviceRow.getTypeLane())
                .lastSeen(firstDeviceRow.getLastSeen())
                .location(firstDeviceRow.getLocation())
                .barriers(barrierInfos)
                .build();

        // Trả về thành công mặc định
        return Result.ok("Success", List.of(response));
    }


    public List<DeviceLaneResponse> getAllDevice() {
        List<DeviceLaneRepository> rawData = parkingDeviceRepository.findAllDeviceDetail();

        Map<Long, List<DeviceLaneRepository>> groupedByDevice = rawData.stream()
                .filter(row -> row.getDeviceId() != null)
                .collect(Collectors.groupingBy(DeviceLaneRepository::getDeviceId));

        List<DeviceLaneResponse> responseList = new ArrayList<>();

        for (Map.Entry<Long, List<DeviceLaneRepository>> deviceEntry : groupedByDevice.entrySet()) {
            List<DeviceLaneRepository> deviceRows = deviceEntry.getValue();
            DeviceLaneRepository firstDeviceRow = deviceRows.get(0);


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
                    .firmwareVersion(firstDeviceRow.getFirmwareVersion())
                    .lastSeen(firstDeviceRow.getLastSeen())
                    .laneId(firstDeviceRow.getLaneId())
                    .typeLane(firstDeviceRow.getTypeLane())
                    .location(firstDeviceRow.getLocation())
                    .barriers(barrierInfos)
                    .build());
        }

        return responseList;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
        return (value == null || value.trim().isEmpty()) ? null : Enum.valueOf(enumClass, value);
    }

    public DeviceStatsResponse getDeviceStats() {
        long totalCamera = cameraRepository.count();
        long onlineCamera = cameraRepository.countByStatus(BaseStatus.ACTIVE);

        long totalBarrier = barrierRepository.count();
        long onlineBarrier = barrierRepository.countByParkingDevice_Status(OperatingStatus.ONLINE);

        long totalPi = parkingDeviceRepository.count();
        long onlinePi = parkingDeviceRepository.countByStatus(OperatingStatus.ONLINE);

        return DeviceStatsResponse.builder()
                .totalCamera(totalCamera)
                .onlineCamera(onlineCamera)
                .offlineCamera(totalCamera - onlineCamera)
                .totalBarrier(totalBarrier)
                .onlineBarrier(onlineBarrier)
                .offlineBarrier(totalBarrier - onlineBarrier)
                .totalPi(totalPi)
                .onlinePi(onlinePi)
                .offlinePi(totalPi - onlinePi)
                .build();
    }

}
