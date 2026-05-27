package vio.barie.core.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.repository.ParkingDeviceRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceHealthScheduler {
    private final ParkingDeviceRepository deviceRepository;

    // Nếu quá 90 giây không ping => OFFLINE
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void markOfflineDevices() {

        LocalDateTime threshold = LocalDateTime.now().minusSeconds(90);

        List<ParkingDeviceEntity> devices =
                deviceRepository.findByLastSeenBeforeAndStatus(
                        threshold,
                        OperatingStatus.ONLINE
                );

        for (ParkingDeviceEntity device : devices) {
            device.setStatus(OperatingStatus.OFFLINE);
            device.setDisconnectReason("PING_TIMEOUT");
        }

        deviceRepository.saveAll(devices);

        if (!devices.isEmpty()) {
            log.warn("Marked {} devices OFFLINE", devices.size());
        }
    }
}
