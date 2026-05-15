package vio.barie.core.service.mqtt;

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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingMessageService {
    private final ParkingMessageRepository messageRepository;
    private final ParkingDeviceRepository deviceRepository;
    private final BarrierRepository barrierRepository;
    private final MQTTPublisher mqttPublisher;
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

        json.path("barriers").forEach(barrier -> {
            BarrierEntity saveBarrier = new BarrierEntity();
            saveBarrier.setBarrierId(barrier.path("barrier_id").asText());
            saveBarrier.setDirection(barrier.path("direction").asText());
            saveBarrier.setDescription(barrier.path("description").asText());
            saveBarrier.setModbusAddr(barrier.path("modbus_addr").asInt());
            saveBarrier.setParkingDevice(device);

            BarrierEntity existing = barrierRepository.findByBarrierId(saveBarrier.getBarrierId()).orElse(null);
            if (existing != null) {
                existing.setDirection(saveBarrier.getDirection());
                existing.setDescription(saveBarrier.getDescription());
                existing.setModbusAddr(saveBarrier.getModbusAddr());
                existing.setParkingDevice(device);
                barrierRepository.save(existing);
            } else {
                barrierRepository.save(saveBarrier);
            }
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
    public void handleEvent(String topic, JsonNode json) {
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

    public void publishEntryVehicleInfo(String barrierId, String plate,
            LocalDateTime entryTime) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishVehicleInfo: barrierId is null or not found");
            return;
        }

        VehicleInfoCommand cmd = VehicleInfoCommand.builder()
                .sessionId("entry_auth_" + LocalDateTime.now())
                .scenarioTag("entry_authorized")
                .barrierId(barrierId)
                .direction("entry")
                .plate(plate)
                .ticketType("free")
                .entryTime(entryTime != null ? entryTime.format(DT_FMT) : null)
                .amountDue(0)
                .currency("VND")
                .open(false)
                .build();

        publish(deviceId, cmd);
    }

    public void publishVehicleInfo(String barrierId, Long sessionId, String plate,
            LocalDateTime entryTime, Integer amountDue, String qrUrl) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishVehicleInfo: barrierId is null or not found, skipping — sessionId={}", sessionId);
            return;
        }

        String now = LocalDateTime.now().format(DT_FMT);
        int durationMinutes = entryTime != null
                ? (int) java.time.Duration.between(entryTime, LocalDateTime.now()).toMinutes()
                : 0;

        VehicleInfoCommand cmd = VehicleInfoCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierId(barrierId)
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

    public void publishOpenForMonthlyTicket(String barrierId, Long sessionId, String plate,
            String ownerName, LocalDateTime validUntil) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishOpenForMonthlyTicket: barrierId is null or not found, skipping — sessionId={}", sessionId);
            return;
        }

        VehicleInfoCommand infoCmd = VehicleInfoCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierId(barrierId)
                .direction("exit")
                .plate(plate)
                .ticketType("monthly")
                .open(true)
                .ownerName(ownerName)
                .validUntil(validUntil != null ? validUntil.format(DT_FMT) : null)
                .build();

        publish(deviceId, infoCmd);
    }

    public void publishPaymentSuccess(String barrierId, Long sessionId, String plate) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishPaymentSuccess: barrierId is null or not found, skipping — sessionId={}", sessionId);
            return;
        }

        PaymentSuccessCommand successCmd = PaymentSuccessCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierIds(List.of(barrierId))
                .plate(plate)
                .status("success")
                .autoIdleSeconds(0)
                .timestamp(LocalDateTime.now().format(DT_FMT))
                .build();

        publish(deviceId, successCmd);
    }

    public void publishPaymentConfirmed(String barrierId, Long sessionId, int amountPaid, String invoiceNo) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishPaymentSuccess: barrierId is null or not found, skipping — sessionId={}", sessionId);
            return;
        }

        PaymentConfirmedCommand confirmedCmd = PaymentConfirmedCommand.builder()
                .sessionId(String.valueOf(sessionId))
                .barrierId(barrierId)
                .amountPaid(amountPaid)
                .transactionId(invoiceNo)
                .timestamp(LocalDateTime.now().format(DT_FMT))
                .build();

        publish(deviceId, confirmedCmd);
    }

    public void publishOpenCommand(String barrierId, Long sessionId, String reason) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishOpenCommand: barrierId is null or not found, skipping — sessionId={}", sessionId);
            return;
        }

        OpenCmdCommand cmd = OpenCmdCommand.builder()
                .barrierId(barrierId)
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
            mqttPublisher.publish(topic, json);
            log.info("Published cmd to topic={}", topic);
        } catch (Exception e) {
            log.error("Failed to publish to deviceId={}: {}", deviceId, e.getMessage(), e);
        }
    }

    private String resolveDeviceId(String barrierId) {
        if (barrierId == null || barrierId.isBlank()) {
            return null;
        }

        return barrierRepository.findByBarrierIdWithDevice(barrierId)
                .map(BarrierEntity::getParkingDevice)
                .map(ParkingDeviceEntity::getDeviceId)
                .orElse(null);
    }

}