package vio.barie.core.service.mqtt;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class MQTTPublisher {

    private final MqttClient client;

    public void publish(String topic, String payload) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(1);
            client.publish(topic, message);
        } catch (Exception e) {
            log.error("Publish error", e);
        }
    }

    public void publish(String topic, String payload, boolean retained) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(retained);
            client.publish(topic, message);
        } catch (Exception e) {
            log.error("Publish error topic={}", topic);
        }
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