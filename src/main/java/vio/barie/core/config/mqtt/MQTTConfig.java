package vio.barie.core.config.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MQTTConfig {

    @Value("${mqtt.host}")
    private String broker;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Bean(destroyMethod = "disconnect")
    public MqttClient mqttClient() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{broker});
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(20);

        // Nếu có auth:
        // options.setUserName("username");
        // options.setPassword("password".toCharArray());
        MqttClient client = new MqttClient(broker, clientId);

        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        client.connect(options);
        log.info("Connected to MQTT broker: {}", broker);
        return client;
    }

}
