package vio.barie.core.service.mqtt;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vio.barie.core.common.HealthStatus;
import vio.barie.core.dto.health.InternetHealthEvent;

import java.time.Instant;



@Slf4j
@Component
@RequiredArgsConstructor
public class InternetHealthEventPublisher {
    private final MQTTPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    @Value("${mqtt.internet-health-check-topic}")
    private String topicInternetHealthCheck;
    public void publishStatusChange(HealthStatus prev, HealthStatus current, String reason, Long downtime) {
        InternetHealthEvent event = InternetHealthEvent.builder()
                .event("INTERNET_HEALTH_CHANGED")
                .previousStatus(prev.name())
                .status(current.name())
                .reason(reason)
                .changedAt(Instant.now().toString())
                .downtimeSeconds(downtime)
                .build();

        try {
            // Chuyển đổi Object thành JSON String
            String payload = objectMapper.writeValueAsString(event);
            log.info("Publishing Internet Health Event to MQTT Topic [{}]: {}", topicInternetHealthCheck, payload);

            // Gọi hàm publish từ service MQTT
            mqttPublisher.publish(topicInternetHealthCheck, payload);

        } catch (Exception e) {
            log.error("Không thể publish sự kiện trạng thái kết nối Internet lên MQTT", e);
        }
    }
}
