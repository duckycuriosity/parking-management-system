package vio.barie.core.config.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import vio.barie.core.dto.parking.PendingEventDetection;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, PendingEventDetection> pendingEventRedisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {
        RedisTemplate<String, PendingEventDetection> template = new RedisTemplate<>();
        Jackson2JsonRedisSerializer<PendingEventDetection> serializer =
                new Jackson2JsonRedisSerializer<>(PendingEventDetection.class);
        serializer.setObjectMapper(objectMapper);

        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
