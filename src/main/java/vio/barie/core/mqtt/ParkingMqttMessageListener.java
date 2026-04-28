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
            if (topic.endsWith("/connect")) {
                parkingMessageService.handleConnect(topic, json);
                return;
            }
            if (topic.endsWith("/disconnect")) {
                parkingMessageService.handleDisconnect(topic, json);
                return;
            }

            if (topic.endsWith("/ping")) {
                parkingMessageService.handlePing(topic, json);
                return;
            }
            log.warn("Unsupported top {}", topic);
        } catch (Exception ex) {
            log.error("MQTT process failed", ex);
        }
    }
}
