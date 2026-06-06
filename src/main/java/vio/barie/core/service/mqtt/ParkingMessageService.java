package vio.barie.core.service.mqtt;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vio.barie.core.common.BarrierActionType;
import vio.barie.core.common.OperatingStatus;
import vio.barie.core.dto.LockCmdCommandResponse;
import vio.barie.core.dto.parking.OpenCmdCommand;
import vio.barie.core.dto.parking.PaymentConfirmedCommand;
import vio.barie.core.dto.parking.PaymentSuccessCommand;
import vio.barie.core.dto.parking.VehicleInfoCommand;
import vio.barie.core.entity.BarrierEntity;
import vio.barie.core.entity.ParkingDeviceEntity;
import vio.barie.core.entity.ParkingMessageEntity;
import vio.barie.core.repository.BarrierRepository;
import vio.barie.core.repository.ParkingDeviceRepository;
import vio.barie.core.repository.ParkingMessageRepository;
import vio.barie.core.common.PaymentCallbackType;
import vio.barie.core.dto.parking.*;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.service.PaymentService;
import vio.barie.core.utils.DetectionType;
import vio.barie.core.utils.DirectionType;
import vio.barie.core.utils.MqttErrorCommand;

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
    private final ParkingSessionRepository parkingSessionRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;
    private final MQTTPublisher mqttPublisher;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Value("${mqtt.publish-cmd-topic-prefix}")
    private String prefix;
    @Value("${mqtt.publish-cmd-topic-suffix}")
    private String suffix;
    @Value("${mqtt.publish-error-topic-suffix}")
    private String error;
    private final ObjectMapper mapper = new ObjectMapper();

    public void handleConnect(String topic, JsonNode json) {
        try {
            String deviceId = json.path("device_id").asText();
            saveHistory(topic, json, "connect");

            ParkingDeviceEntity device = deviceRepository.findByDeviceId(deviceId)
                    .orElse(ParkingDeviceEntity.builder()
                            .deviceId(deviceId)
                            .createdAt(LocalDateTime.now())
                            .build());
            device.setStatus(OperatingStatus.ONLINE);
            device.setFirmwareVersion(json.path("firmware_version").asText(null));
            device.setHasCashReader(json.path("has_cash_reader").asBoolean());
            device.setHasDisplay(json.path("has_display").asBoolean());
            device.setLastSeen(LocalDateTime.now());

            // ===== FIX: Dùng biến mới savedDevice =====
            ParkingDeviceEntity savedDevice = deviceRepository.save(device);
            log.debug("Device saved first: id={} deviceId={}", savedDevice.getId(), deviceId);

            json.path("barriers").forEach(barrier -> {
                BarrierEntity saveBarrier = new BarrierEntity();
                saveBarrier.setBarrierId(barrier.path("barrier_id").asText());
                String directionStr = barrier.path("direction").asText();
                if (directionStr != null && !directionStr.isBlank()) {
                    try {
                        saveBarrier.setDirection(DirectionType.valueOf(directionStr.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid direction value from MQTT payload for barrier {}: {}",
                                saveBarrier.getBarrierId(), directionStr);
                    }
                }
                saveBarrier.setDescription(barrier.path("description").asText());
                saveBarrier.setModbusAddr(barrier.path("modbus_addr").asInt());

                saveBarrier.setParkingDevice(savedDevice);

                BarrierEntity existing = barrierRepository.findByBarrierId(saveBarrier.getBarrierId()).orElse(null);
                if (existing != null) {
                    existing.setDirection(saveBarrier.getDirection());
                    existing.setDescription(saveBarrier.getDescription());
                    existing.setModbusAddr(saveBarrier.getModbusAddr());
                    existing.setParkingDevice(savedDevice);
                    barrierRepository.save(existing);
                } else {
                    barrierRepository.save(saveBarrier);
                }
            });

            // Cập nhật lại device nếu cần
            deviceRepository.save(savedDevice);
            log.info("Device connected: {}", deviceId);

        } catch (Exception ex) {
            log.error("ParkingMessageService.handleConnect failed topic={}: {}", topic, ex.getMessage(), ex);
        }
    }

    public void handleDisconnect(String topic, JsonNode json) {
        try {
            String deviceId = json.path("device_id").asText();
            String reason = json.path("reason").asText();
            saveHistory(topic, json, "disconnect");
            deviceRepository.findByDeviceId(deviceId)
                    .ifPresent(device -> {
                        device.setStatus(OperatingStatus.OFFLINE);
                        device.setDisconnectReason(reason);
                        device.setUpdatedAt(LocalDateTime.now());
                        deviceRepository.save(device);
                    });
            log.info("Device disconnected: {} reason={}", deviceId, reason);
        } catch (Exception ex) {
            log.error("ParkingMessageService.handleDisconnect failed topic={}: {}", topic, ex.getMessage(), ex);
        }
    }

    public void handlePing(String topic, JsonNode json) {
        try {
            String deviceId = json.path("device_id").asText();
            saveHistory(topic, json, "ping");
            ParkingDeviceEntity device = deviceRepository.findByDeviceId(deviceId)
                    .orElse(ParkingDeviceEntity.builder()
                            .deviceId(deviceId)
                            .createdAt(LocalDateTime.now())
                            .build());
            device.setStatus(OperatingStatus.ONLINE);
            device.setLastSeen(LocalDateTime.now());
            device.setUptimeSeconds(json.path("uptime_seconds").asLong());
            device.setFreeMemMb(json.path("free_mem_mb").asInt());
            if (json.has("cpu_temp_c")) {
                device.setCpuTempC(BigDecimal.valueOf(json.path("cpu_temp_c").asDouble()));
            }
            deviceRepository.save(device);
            log.info("Ping updated: {}", deviceId);
        } catch (Exception ex) {
            log.error("ParkingMessageService.handlePing failed topic={}: {}", topic, ex.getMessage(), ex);
        }
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

    public void handlePayment(String topic, JsonNode json) {
        try {
            saveHistory(topic, json, "payment");

            PaymentMessage msg = objectMapper.treeToValue(json, PaymentMessage.class);
            String method = msg.getMethod() == null ? "" : msg.getMethod().trim().toLowerCase();

            log.info("Payment received: deviceId={} sessionId={} method={} amountDue={} amountPaid={}",
                    msg.getDeviceId(), msg.getSessionId(), method, msg.getAmountDue(), msg.getAmountPaid());

            if ("cash".equals(method)) {
                PaymentSuccessCommand result = handleCashPayment(msg);
                if (result != null) {
                    publishPaymentSuccess(result);
                } else {
                    log.error("ParkingMessageService.handlePayment type CASH failed on topic: {}, jsonNode: {} !", topic, json);
                }
            }

            // QR payments được xác nhận qua MegaPay IPN (PaymentService.handleIPN)
            log.debug("handlePayment: ignoring non-cash payment ack — method={} sessionId={}",
                    method, msg.getSessionId());
        } catch (Exception ex) {
            log.error("ParkingMessageService.handlePayment failed topic={}: {}", topic, ex.getMessage(), ex);
        }
    }

    public PaymentSuccessCommand handleCashPayment(PaymentMessage msg) {
        Long sessionId;
        try {
            sessionId = Long.parseLong(msg.getSessionId());
        } catch (NumberFormatException ex) {
            log.warn("handleCashPayment: invalid sessionId={}", msg.getSessionId());
            return null;
        }

        ParkingSessionEntity session = parkingSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("handleCashPayment: session not found id={}", sessionId);
            return null;
        }

        // có thể lấy từ payment context nhưng sẽ ưu tiên trường được gửi lên hơn
        String barrierId = msg.getBarrierId();
        String plate = msg.getPlate();

        // Tìm payment đã được tạo khi xe ra (thường là PENDING/QR record)
        PaymentEntity payment = paymentRepository
                .findLatestBySessionId(String.valueOf(sessionId))
                .orElse(null);
        if (payment == null) {
            log.warn("handleCashPayment: no payment record found for sessionId={} — skip", sessionId);
            return null;
        }

        // Idempotency: payment đã PAID rồi → không cập nhật DB nữa,
        // nhưng vẫn publish payment_success phòng khi thiết bị bị mất gói lần trước
        if ("PAID".equalsIgnoreCase(payment.getStatus())) {
            log.info("handleCashPayment: payment id={} already PAID for sessionId={} — re-publishing payment_success",
                    payment.getId(), sessionId);
            return PaymentSuccessCommand
                    .builder()
                    .sessionId(sessionId)
                    .barrierIds(List.of(barrierId))
                    .plate(plate)
                    .build();
        }

        int amountDue = msg.getAmountDue() == null ? 0 : msg.getAmountDue();
        int amountPaid = msg.getAmountPaid() == null ? 0 : msg.getAmountPaid();
        if (amountPaid < amountDue) {
            log.warn("handleCashPayment: amountPaid={} < amountDue={} sessionId={}",
                    amountPaid, amountDue, sessionId);
            // todo: nên gửi về tbao error cho bên Hiển
            return null;
        }

        int amountChange = msg.getAmountChange() != null ? msg.getAmountChange() : (amountPaid - amountDue);

        // Cập nhật payment có sẵn → PAID/CASH
        payment.setPaymentMethod("CASH");
        payment.setStatus("PAID");
        payment.setPaidAt(LocalDateTime.now());
        payment.setResultCode("CASH_OK");
        payment.setResultMessage("Cash payment confirmed by device");

        paymentRepository.save(payment);

        log.info("handleCashPayment: payment id={} updated to PAID(CASH) sessionId={} paid={} change={}",
                payment.getId(), sessionId, amountPaid, amountChange);

        return PaymentSuccessCommand
                .builder()
                .sessionId(sessionId)
                .barrierIds(List.of(barrierId))
                .plate(plate)
                .build();
    }

    public void handleEvent(String topic, JsonNode json) {
        try {
            // placeholder for event handling
        } catch (Exception ex) {
            log.error("ParkingMessageService.handleEvent failed topic={}: {}", topic, ex.getMessage(), ex);
        }
    }

    public void handleSessionAbandoned(String topic, JsonNode json) {
        try {
            String deviceId = json.path("device_id").asText();
            String sessionId = json.path("session_id").asText();
            saveHistory(topic, json, "session_abandoned");
            log.info("Session abandoned: deviceId={} sessionId={}", deviceId, sessionId);
        } catch (Exception ex) {
            log.error("ParkingMessageService.handleSessionAbandoned failed topic={}: {}", topic, ex.getMessage(), ex);
        }
    }

    public void handleSupportRequest(String topic, JsonNode json) {
        try {
            String deviceId = json.path("device_id").asText();
            String sessionId = json.path("session_id").asText();
            String reason = json.path("reason").asText();
            saveHistory(topic, json, "support_request");
            log.info("Support request: deviceId={} sessionId={} reason={}", deviceId, sessionId, reason);
        } catch (Exception ex) {
            log.error("ParkingMessageService.handleSupportRequest failed topic={}: {}", topic, ex.getMessage(), ex);
        }
    }

    public void publishEntryVehicleInfo(String barrierId, String plate,
                                        LocalDateTime entryTime) {
        String deviceId = resolveDeviceId(barrierId);
        if (deviceId == null) {
            log.warn("publishVehicleInfo: barrierId is null or not found");
            return;
        }

        VehicleInfoCommand cmd = VehicleInfoCommand.builder()
                .session_id("entry_auth_" + LocalDateTime.now())
                .scenarion_tag("entry_authorized")
                .barrier_id(barrierId)
                .direction("entry")
                .plate(plate)
                .ticket_type("free")
                .entry_time(entryTime != null ? entryTime.format(DT_FMT) : null)
                .amount_due(BigDecimal.valueOf(0))
                .currency("VND")
                .open(false)
                .build();

        publish(deviceId, cmd);
    }

    public void publishVehicleInfo(String barrierId, Long sessionId, String plate,
                                   LocalDateTime entryTime, BigDecimal amountDue, String qrUrl) {
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
                .session_id(String.valueOf(sessionId))
                .barrier_id(barrierId)
                .direction("exit")
                .plate(plate)
                .ticket_type("hourly")
                .entry_time(entryTime != null ? entryTime.format(DT_FMT) : null)
                .exit_time(now)
                .duration_minutes(durationMinutes)
                .amount_due(amountDue)
                .currency("VND")
                .payment_methods(List.of("qr", "cash"))
                .qr_url(qrUrl)
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
                .session_id(String.valueOf(sessionId))
                .barrier_id(barrierId)
                .direction("exit")
                .plate(plate)
                .ticket_type("monthly")
                .open(true)
                .owner_name(ownerName)
                .valid_until(validUntil != null ? validUntil.format(DT_FMT) : null)
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
                .sessionId(sessionId)
                .barrierIds(List.of(barrierId))
                .plate(plate)
                .status("success")
                .autoIdleSeconds(0)
                .timestamp(LocalDateTime.now().format(DT_FMT))
                .build();

        publish(deviceId, successCmd);
    }

    public void publishPaymentSuccess(PaymentSuccessCommand cmd) {
        String deviceId = resolveDeviceId(cmd.getBarrierIds().getFirst());
        if (deviceId == null) {
            log.warn("publishPaymentSuccess: barrierId is null or not found, skipping — sessionId={}", cmd.getSessionId());
            return;
        }

        publish(deviceId, cmd);
    }

    public void publishPaymentConfirmed(String barrierId, Long sessionId, BigDecimal amountPaid, String invoiceNo) {
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
                .autoCloseSeconds(30)
                .build();

        publish(deviceId, cmd);
    }

    public void publishLockUnlockCommand(String barrierId, BarrierActionType action) {
        // tìm barrier trong DB để cập nhật trạng thái
        BarrierEntity barrier = barrierRepository.findByBarrierId(barrierId).orElse(null);
        if (barrier == null) {
            log.warn("publishLockUnlockCommand: Barrier not found in DB with barrierId={}", barrierId);
            return;
        }
        // cập nhật trạng thái action mới (LOCK/UNLOCK) vào DB
        barrier.setAction(action);
        barrierRepository.save(barrier);
        log.info("The DB memory state has been updated to {} cho barrierId={}", action, barrierId);
        // lấy deviceId để build topic và publish lệnh xuống thiết bị phần cứng
        ParkingDeviceEntity device = barrier.getParkingDevice();
        if (device == null || device.getDeviceId() == null) {
            log.warn("publishLockUnlockCommand: Barrier chưa được gán vào Device nào — barrierId={}", barrierId);
            return;
        }
        String deviceId = device.getDeviceId();
        // gửi đi MQTT
        LockCmdCommandResponse cmd = LockCmdCommandResponse.builder()
                .barrierId(barrierId)
                .action(action)
                .build();

        publish(deviceId, cmd);
        log.info("Đã gửi lệnh MQTT {} tới barrierId={} (Device={})", action, barrierId, deviceId);
    }

    public void publishDisplayMsg() {
    }

    public void publishReconnect() {
    }

    private void publish(String deviceId, Object payload) {
        try {
            String topic = prefix + "/" + deviceId + suffix;
            String json = mapper.writeValueAsString(payload);
            mqttPublisher.publish(topic, json);
            log.info("Published cmd to topic={}", topic);
        } catch (Exception e) {
            log.error("Failed to publish to deviceId={}: {}", deviceId, e.getMessage(), e);
        }
    }

    public void publishErrorWhenHandlingEvent(String message, String cameraUUID, DetectionType type) {
        publishErrorWhenHandlingEvent(message, cameraUUID, type, null);
    }

    public void publishErrorWhenHandlingEvent(String message, String cameraUUID, DetectionType type, String barrierId) {
        if (barrierId == null || barrierId.isBlank()) {
            log.warn("Cannot publish error event without barrierId: message={} cameraUUID={} type={}", message, cameraUUID, type);
            return;
        }

        try {
            String topic = buildErrorTopic(barrierId);
            MqttErrorCommand payload = new MqttErrorCommand();
            payload.setMessage(message);
            payload.setCameraUUID(cameraUUID);
            payload.setType(type);
            payload.setBarrierId(barrierId);
            String json = JSONUtil.toJsonStr(payload);
            mqttPublisher.publish(topic, json);
            log.info("Published error event to topic={}", topic);
        } catch (Exception e) {
            log.error("Failed to publish error event to barrierId={}: {}", barrierId, e.getMessage(), e);
        }
    }

    private String buildErrorTopic(String barrierId) {
        String normalizedPrefix = prefix == null ? "" : prefix.replaceAll("/+$", "");
        String normalizedError = error == null ? "/error" : error.startsWith("/") ? error : "/" + error;
        return normalizedPrefix + "/" + barrierId + normalizedError;
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