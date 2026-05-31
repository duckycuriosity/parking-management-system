package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.ticket.TicketSummaryResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketSummaryCacheService {
    private static final String PREFIX = "ticket:summary:";
    private final StringRedisTemplate redisTemplate;

    public void syncAllCounters(long total, long active, long expired, long expiringSoon) {
        redisTemplate.opsForValue().set(PREFIX + "total", String.valueOf(total));
        redisTemplate.opsForValue().set(PREFIX + "active", String.valueOf(active));
        redisTemplate.opsForValue().set(PREFIX + "expired", String.valueOf(expired));
        redisTemplate.opsForValue().set(PREFIX + "expiring_soon", String.valueOf(expiringSoon));
    }

    public TicketSummaryResponse getSummary() {
        return TicketSummaryResponse.builder()
                .total(getLong("total"))
                .active(getLong("active"))
                .expired(getLong("expired"))
                .expiringSoon(getLong("expiring_soon"))
                .build();
    }

    // tăng/giảm Realtime khi có action cụ thể
    public void increment(String key) {
        redisTemplate.opsForValue().increment(PREFIX + key);
    }

    public void decrement(String key) {
        redisTemplate.opsForValue().decrement(PREFIX + key);
    }

    private Long getLong(String key) {
        String val = redisTemplate.opsForValue().get(PREFIX + key);
        if (val == null) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
