package vio.barie.core.config.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
public class MQTTConfig {

    @Value("${mqtt.host}")
    private String broker;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.queue.core-pool-size}")
    private Integer corePoolSize;

    @Value("${mqtt.queue.max-pool-size}")
    private Integer maxPoolSize;

    @Value("${mqtt.queue.queue-capacity}")
    private Integer queueCapacity;

    @Bean(destroyMethod = "disconnect")
    public MqttClient mqttClient() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] { broker });
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);

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

    @Bean(name = "mqttExecutor")
    public Executor mqttExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("mqtt-handler-");
        executor.initialize();
        return executor;
    }

}
