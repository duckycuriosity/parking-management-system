package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MQTTSubcriber {

    private final MqttClient client;
    private final ProfileMessageService profileMessageService;
    private final PlateMessageService plateMessageService;
    private final ParkingMessageService parkingMessageService;
    private final ObjectMapper objectMapper;

    @Value("${mqtt.device-register-topic}")
    private String deviceRegisterTopic;
    @Value("${mqtt.aibox-face-topic}")
    private String aiboxFaceTopic;
    @Value("${mqtt.aibox-plate-topic}")
    private String aiboxPlateTopic;

    @PostConstruct
    public void init() {
        try {
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.error("Connection lost", cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    log.info("Received from {}: {}", topic, payload);
                    listen(topic, payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    log.info("Delivery complete");
                }
            });

            // Subscribe default topic
            client.subscribe(deviceRegisterTopic);
            client.subscribe(aiboxFaceTopic);
            client.subscribe(aiboxPlateTopic);

        } catch (Exception e) {
            log.error("MQTT init error", e);
        }
    }

    public void listen(String topic, String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            String type = json.path("type").asText("");

            if (type.isBlank()) {
                type = extractTypeFromTopic(topic);
            }

            dispatch(topic, type, json);
        } catch (Exception e) {
            log.error("MQTT listen error. topic={}, payload={}", topic, payload, e);
        }
    }

    private void dispatch(String topic, String type, JsonNode json) {
        if(topic.equals(aiboxFaceTopic)){
            profileMessageService.handle(json);
        } else if (topic.equals(aiboxPlateTopic)) {
            plateMessageService.handle(json);
        } else {
            String normalizedType = type == null ? "" : type.trim().toLowerCase();

            switch (normalizedType) {
                case "connect" -> parkingMessageService.handleConnect(topic, json);
                case "disconnect" -> parkingMessageService.handleDisconnect(topic, json);
                case "ping" -> parkingMessageService.handlePing(topic, json);
                case "payment" -> parkingMessageService.handlePayment(topic, json);
                case "event" -> parkingMessageService.handleEvent(topic, json);
                default -> log.warn("Unsupported MQTT message type={}, topic={}", type, topic);
            }
        }
    }

    private String extractTypeFromTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return "";
        }

        int lastSlash = topic.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == topic.length() - 1) {
            return "";
        }

        return topic.substring(lastSlash + 1);
    }

    @PreDestroy
    public void destroy() {
        try {
            client.disconnect();
        } catch (Exception e) {
            log.error("Disconnect error", e);
        }
    }
}