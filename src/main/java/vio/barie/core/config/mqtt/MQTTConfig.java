package vio.barie.core.config.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
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

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.queue.core-pool-size}")
    private Integer corePoolSize;

    @Value("${mqtt.queue.max-pool-size}")
    private Integer maxPoolSize;

    @Value("${mqtt.queue.queue-capacity}")
    private Integer queueCapacity;

    @Bean
    public MqttConnectOptions mqttConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
        }
        if (password != null && !password.isEmpty()) {
            options.setPassword(password.toCharArray());
        }
        options.setServerURIs(new String[] { broker });
        options.setAutomaticReconnect(true);
        // Conservative defaults for unstable networks; tune as needed
        options.setCleanSession(true);
        options.setConnectionTimeout(60);
        options.setKeepAliveInterval(120);
        options.setMaxInflight(100);
        log.info("MQTT connect options: cleanSession={}, connectionTimeout={}, keepAlive={}, maxInflight={}",
                options.isCleanSession(), options.getConnectionTimeout(), options.getKeepAliveInterval(),
                options.getMaxInflight());
        return options;
    }

    @Bean(destroyMethod = "disconnect")
    public MqttClient mqttClient() throws MqttException {
        // Nếu có auth:
        // options.setUserName("username");
        // options.setPassword("password".toCharArray());
        MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
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
