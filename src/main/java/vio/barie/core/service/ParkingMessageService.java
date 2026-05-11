package vio.barie.core.service;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.parking.OpenCmdCommand;
import vio.barie.core.dto.parking.PaymentConfirmedCommand;
import vio.barie.core.dto.parking.PaymentSuccessCommand;
import vio.barie.core.dto.parking.VehicleInfoCommand;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.ParkingDeviceEntity;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.ParkingMessageEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.repository.ParkingMessageRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingMessageService {
    private final ParkingMessageRepository messageRepository;
    private final ParkingDeviceRepository deviceRepository;
    private final BarrierRepository barrierRepository;
    private final MQTTService mqttService;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Value("${mqtt.publish-cmd-topic-prefix}")
    private String prefix;
    @Value("${mqtt.publish-cmd-topic-suffix}")
    private String suffix;

    @Transactional
    public void handleConnect(String topic, JsonNode json) {
        String deviceId = json.path("device_id").asText();
        saveHistory(topic, json, "connect");

        ParkingDeviceEntity device = deviceRepository.findByDeviceId(deviceId)
                .orElse(ParkingDeviceEntity.builder()
                        .deviceId(deviceId)
                        .createdAt(LocalDateTime.now())
                        .build());
        device.setStatus("Online");
        device.setFirmwareVersion(json.path("firmware_version").asText(null));
        device.setHasCashReader(json.path("has_cash_reader").asBoolean());
        device.setHasDisplay(json.path("has_display").asBoolean());
        device.setLastSeen(LocalDateTime.now());

        // TODO: Save barrier info: model, soc, modbus_addr, etc.
        json.path("barriers").forEach(barrierJson -> {
            BarrierEntity barrier = new BarrierEntity();
            barrier.setBarrierId(barrierJson.path("barrier_id").asText());
            barrier.setParkingDevice(device);
            barrier.setDescription(barrierJson.path("description").asText(null));
            barrier.setDirection(barrierJson.path("direction").asText(null));
            if (barrierJson.has("modbus_addr") && !barrierJson.path("modbus_addr").isNull()) {
                barrier.setModbusAddr(barrierJson.path("modbus_addr").asInt());
            }
            // Save the barrier entity
            barrierRepository.save(barrier);
        });
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

        ParkingDeviceEntity device = deviceRepository.findByDeviceId(deviceId)
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
                    BigDecimal.valueOf(json.path("cpu_temp_c").asDouble()));
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
                                : null)
                .freeMemMb(
                        json.has("free_mem_mb")
                                ? json.path("free_mem_mb").asInt()
                                : null)
                .cpuTempC(
                        json.has("cpu_temp_c")
                                ? BigDecimal.valueOf(
                                        json.path("cpu_temp_c").asDouble())
                                : null)
                .createdAt(LocalDateTime.now())
                .build();

        messageRepository.save(entity);
    }

    @Transactional
    public void handlePayment(String topic, JsonNode json) {
        String deviceId = json.path("device_id").asText();
        String sessionId = json.path("session_id").asText();
        saveHistory(topic, json, "payment");
        log.info("Payment received: deviceId={} sessionId={}", deviceId, sessionId);
        // TODO: xác nhận tiền mặt, gửi payment_success xuống thiết bị
    }

    @Transactional
    public void handleSessionAbandoned(String topic, JsonNode json) {
        String deviceId = json.path("device_id").asText();
        String sessionId = json.path("session_id").asText();
        saveHistory(topic, json, "session_abandoned");
        log.info("Session abandoned: deviceId={} sessionId={}", deviceId, sessionId);
        // TODO: giải phóng session, release trạng thái chờ thanh toán
    }

    @Transactional
    public void handleSupportRequest(String topic, JsonNode json) {
        String deviceId = json.path("device_id").asText();
        String sessionId = json.path("session_id").asText();
        String reason = json.path("reason").asText();
        saveHistory(topic, json, "support_request");
        log.info("Support request: deviceId={} sessionId={} reason={}", deviceId, sessionId, reason);
        // TODO: alert ops dashboard, chờ manual override
    }

    public void publishVehicleInfo(String deviceId, Long sessionId, String plate,
            LocalDateTime entryTime, Integer amountDue, String qrUrl) {
        if (deviceId == null) {
            log.warn("publishVehicleInfo: deviceId is null, skipping — sessionId={}", sessionId);
            return;
        }

        String now = LocalDateTime.now().format(DT_FMT);
        int durationMinutes = entryTime != null
                ? (int) java.time.Duration.between(entryTime, LocalDateTime.now()).toMinutes()
                : 0;

        VehicleInfoCommand cmd = VehicleInfoCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierId(deviceId + "_01")
                .direction("exit")
                .plate(plate)
                .ticketType("hourly")
                .entryTime(entryTime != null ? entryTime.format(DT_FMT) : null)
                .exitTime(now)
                .durationMinutes(durationMinutes)
                .amountDue(amountDue)
                .currency("VND")
                .paymentMethods(List.of("qr", "cash"))
                .qrUrl(qrUrl)
                .build();

        publish(deviceId, cmd);
    }

    public void publishOpenForMonthlyTicket(String deviceId, Long sessionId, String plate,
            String ownerName, LocalDateTime validUntil) {
        if (deviceId == null) {
            log.warn("publishOpenForMonthlyTicket: deviceId is null, skipping — sessionId={}", sessionId);
            return;
        }

        VehicleInfoCommand infoCmd = VehicleInfoCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierId(deviceId + "_01")
                .direction("exit")
                .plate(plate)
                .ticketType("monthly")
                .open(true)
                .ownerName(ownerName)
                .validUntil(validUntil != null ? validUntil.format(DT_FMT) : null)
                .build();

        publish(deviceId, infoCmd);
    }

    public void publishPaymentSuccess(String deviceId, Long sessionId, String plate) {
        if (deviceId == null) {
            log.warn("publishPaymentSuccess: deviceId is null, skipping — sessionId={}", sessionId);
            return;
        }

        PaymentSuccessCommand successCmd = PaymentSuccessCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierIds(List.of(deviceId + "_01"))
                .plate(plate)
                .status("success")
                .autoIdleSeconds(0)
                .timestamp(LocalDateTime.now().format(DT_FMT))
                .build();

        publish(deviceId, successCmd);
    }

    public void publishPaymentConfirmed(String deviceId, Long sessionId, int amountPaid, String invoiceNo) {
        if (deviceId == null) {
            log.warn("publishPaymentSuccess: deviceId is null, skipping — sessionId={}", sessionId);
            return;
        }

        PaymentConfirmedCommand confirmedCmd = PaymentConfirmedCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierId(deviceId + "_01")
                .amountPaid(amountPaid)
                .transactionId(invoiceNo)
                .timestamp(LocalDateTime.now().format(DT_FMT))
                .build();

        publish(deviceId, confirmedCmd);
    }

    public void publishOpenCommand(String deviceId, Long sessionId, String plate, String reason) {
        if (deviceId == null) {
            log.warn("publishOpenCommand: deviceId is null, skipping — sessionId={}", sessionId);
            return;
        }

        OpenCmdCommand cmd = OpenCmdCommand.builder()
                .barrierId(deviceId + "_01")
                .sessionId(String.valueOf(sessionId))
                .reason(reason)
                .playSound(true)
                .soundFile("welcome.mp3")
                .autoCloseSeconds(5)
                .build();

        publish(deviceId, cmd);
    }

    public void publishDisplayMsg() {
    }

    public void publishReconnect() {
    }

    private void publish(String deviceId, Object payload) {
        try {
            String topic = prefix + "/" + deviceId + suffix;
            String json = JSONUtil.toJsonStr(payload);
            mqttService.publish(topic, json);
            log.info("Published cmd to topic={}", topic);
        } catch (Exception e) {
            log.error("Failed to publish to deviceId={}: {}", deviceId, e.getMessage(), e);
        }
    }

}
