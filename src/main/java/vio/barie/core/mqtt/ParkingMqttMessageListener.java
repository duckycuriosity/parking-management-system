package vio.barie.core.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.stereotype.Component;
import vio.barie.core.service.ParkingMessageService;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParkingMqttMessageListener implements MessageHandler {
    private final ObjectMapper objectMapper;
    private final ParkingMessageService parkingMessageService;

    @Override
    public void handleMessage(Message<?> message) {
        try {
            String topic = message.getHeaders()
                    .get("mqtt_receivedTopic", String.class);
            String payload = message.getPayload().toString();
            log.info("MQTT Received topic={} payload={}", topic, payload);

            if (topic == null) {
                log.warn("MQTT topic is null");
                return;
            }
            JsonNode json = objectMapper.readTree(payload);

            String type = json.path("type").asText("");
            if (type.isBlank()) {
                type = extractTypeFromTopic(topic);
            }

            dispatch(topic, type, json);
        } catch (Exception ex) {
            log.error("MQTT process failed", ex);
        }
    }

    private void dispatch(String topic, String type, JsonNode json) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase();

        switch (normalizedType) {
            case "connect" -> parkingMessageService.handleConnect(topic, json);
            case "disconnect" -> parkingMessageService.handleDisconnect(topic, json);
            case "ping" -> parkingMessageService.handlePing(topic, json);
            default -> log.warn("Unsupported MQTT message type={}, topic={}", type, topic);
        }
    }

    private String extractTypeFromTopic(String topic) {
        int lastSlash = topic.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == topic.length() - 1) {
            return "";
        }
        return topic.substring(lastSlash + 1);
    }
}
