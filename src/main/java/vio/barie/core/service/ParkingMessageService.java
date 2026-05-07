package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vio.barie.core.entity.ParkingDeviceEntity;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.ParkingMessageEntity;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.repository.ParkingMessageRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingMessageService {
    private final ParkingMessageRepository messageRepository;
    private final ParkingDeviceRepository deviceRepository;

    @Transactional
    public void handleConnect(String topic, JsonNode json) {
        String deviceId = json.path("device_id").asText();
        saveHistory(topic, json, "connect");

        ParkingDeviceEntity device =
                deviceRepository.findByDeviceId(deviceId)
                        .orElse(ParkingDeviceEntity.builder()
                                .deviceId(deviceId)
                                .createdAt(LocalDateTime.now())
                                .build());
        device.setStatus("Online");
        device.setFirmwareVersion(json.path("firmware_version").asText(null));
        device.setHasCashReader(json.path("has_cash_reader").asBoolean());
        device.setHasDisplay(json.path("has_display").asBoolean());
        device.setLastSeen(LocalDateTime.now());

        deviceRepository.save(device);
        log.info("Device connected: {}", deviceId);
    }

    @Transactional
    public void handleDisconnect(String topic, JsonNode json) {
        String deviceId = json.path("device_id").asText();
        String reason = json.path("reason").asText();
        saveHistory(topic, json, "disconnect");
        deviceRepository.findByDeviceId(deviceId)
                .ifPresent(device -> {
                    device.setStatus("OFFLINE");
                    device.setDisconnectReason(reason);
                    device.setUpdatedAt(LocalDateTime.now());
                    deviceRepository.save(device);
                });

        log.info("Device disconnected: {} reason={}", deviceId, reason);
    }

    @Transactional
    public void handlePing(String topic, JsonNode json) {

        String deviceId = json.path("device_id").asText();

        saveHistory(topic, json, "ping");

        ParkingDeviceEntity device =
                deviceRepository.findByDeviceId(deviceId)
                        .orElse(ParkingDeviceEntity.builder()
                                .deviceId(deviceId)
                                .createdAt(LocalDateTime.now())
                                .build());

        device.setStatus("Online");
        device.setLastSeen(LocalDateTime.now());
        device.setUptimeSeconds(json.path("uptime_seconds").asLong());
        device.setFreeMemMb(json.path("free_mem_mb").asInt());

        if (json.has("cpu_temp_c")) {
            device.setCpuTempC(
                    BigDecimal.valueOf(json.path("cpu_temp_c").asDouble())
            );
        }

        deviceRepository.save(device);

        log.info("Ping updated: {}", deviceId);
    }

    private void saveHistory(String topic, JsonNode json, String type) {

        ParkingMessageEntity entity = ParkingMessageEntity.builder()
                .deviceId(json.path("device_id").asText())
                .messageType(type)
                .topic(topic)
                .payload(json)
                .firmwareVersion(json.path("firmware_version").asText(null))
                .reason(json.path("reason").asText(null))
                .uptimeSeconds(
                        json.has("uptime_seconds")
                                ? json.path("uptime_seconds").asLong()
                                : null
                )
                .freeMemMb(
                        json.has("free_mem_mb")
                                ? json.path("free_mem_mb").asInt()
                                : null
                )
                .cpuTempC(
                        json.has("cpu_temp_c")
                                ? BigDecimal.valueOf(
                                json.path("cpu_temp_c").asDouble()
                        )
                                : null
                )
                .createdAt(LocalDateTime.now())
                .build();

        messageRepository.save(entity);
    }

    public void publishVehicleInfo() {
    }

    // mở cổng trực tiếp cho xe có vé tháng (không thu tiền)
    public void publishOpenForMonthlyTicket() {
    }

    // payment_success: cổng nhận xác nhận và mở barrier
    public void publishPaymentSuccess() {
    }

    // open_cmd: lệnh mở cổng thủ công
    public void publishOpenCommand() {
    }
}
