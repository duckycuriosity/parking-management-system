package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import vio.barie.core.common.OperatingStatus;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.entity.ParkingMessageEntity;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.repository.ParkingMessageRepository;

import java.util.List;

@RestController
@RequestMapping("/api/parking-monitor")
@RequiredArgsConstructor
public class ParkingMonitorController {
    private final ParkingMessageRepository messageRepository;
    private final ParkingDeviceRepository deviceRepository;

    @GetMapping("/devices")
    public List<ParkingDeviceEntity> devices() {
        return deviceRepository.findAll();
    }

    @GetMapping("/devices/online")
    public List<ParkingDeviceEntity> onlineDevices() {
        return deviceRepository.findByStatus(OperatingStatus.ONLINE);
    }

    @GetMapping("/messages/latest")
    public List<ParkingMessageEntity> latestMessages() {
        return messageRepository.findTop100ByOrderByCreatedAtDesc();
    }


    @GetMapping("/messages/{deviceId}")
    public List<ParkingMessageEntity> deviceMessages(
            @PathVariable String deviceId
    ) {
        return messageRepository
                .findTop50ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

}
