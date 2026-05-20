package vio.barie.core.service.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MQTTSubcriber {

    private final MqttClient client;
    private final ProfileMessageService profileMessageService;
    private final PlateMessageService plateMessageService;
    private final ParkingMessageService parkingMessageService;
    private final ObjectMapper objectMapper;
    private final Executor mqttExecutor;
    private final MqttStartupWarmup mqttStartupWarmup;
    private final MqttConnectOptions mqttConnectOptions;
    private final AtomicBoolean subscriptionsRegistered = new AtomicBoolean(false);

    public MQTTSubcriber(MqttClient client, ProfileMessageService profileMessageService,
            PlateMessageService plateMessageService,
            ParkingMessageService parkingMessageService, ObjectMapper objectMapper,
            @Qualifier("mqttExecutor") Executor mqttExecutor,
            MqttStartupWarmup mqttStartupWarmup,
            MqttConnectOptions mqttConnectOptions) {
        this.client = client;
        this.profileMessageService = profileMessageService;
        this.plateMessageService = plateMessageService;
        this.parkingMessageService = parkingMessageService;
        this.objectMapper = objectMapper;
        this.mqttExecutor = mqttExecutor; // Inject the executor for async processing
        this.mqttStartupWarmup = mqttStartupWarmup;
        this.mqttConnectOptions = mqttConnectOptions;
    }

    @Value("${mqtt.device-register-topic}")
    private String deviceRegisterTopic;
    @Value("${mqtt.aibox-face-topic}")
    private String aiboxFaceTopic;
    @Value("${mqtt.aibox-plate-topic}")
    private String aiboxPlateTopic;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.error("Connection lost", cause);
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT connected: reconnect={} serverURI={}", reconnect, serverURI);
                    subscribeTopics();
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    log.debug("Received MQTT message from topic={}", topic);
                    try {
                        mqttExecutor.execute(() -> listen(topic, payload));
                    } catch (RuntimeException ex) {
                        log.error("Failed to enqueue MQTT message topic={}: {}", topic, ex.getMessage(), ex);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    log.info("Delivery complete");
                }
            });

            mqttStartupWarmup.warmUp();
            if (!client.isConnected()) {
                client.connect(mqttConnectOptions);
                log.info("MQTT connected during application ready");
            }
            subscribeTopics();

        } catch (Exception e) {
            log.error("MQTT init error", e);
        }
    }

    private synchronized void subscribeTopics() {
        try {
            if (subscriptionsRegistered.get()) {
                return;
            }

            if (!client.isConnected()) {
                log.info("MQTT client is not connected yet, skip subscribe and wait for reconnect");
                return;
            }

            client.subscribe(deviceRegisterTopic);
            client.subscribe(aiboxFaceTopic);
            client.subscribe(aiboxPlateTopic);
            subscriptionsRegistered.set(true);
            log.info("MQTT subscriptions registered");
        } catch (Exception e) {
            log.error("MQTT subscribe error", e);
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
        if (topic.equals(aiboxFaceTopic)) {
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