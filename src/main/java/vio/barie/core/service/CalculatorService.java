package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.entity.PricingRuleEntity;
import vio.barie.core.repository.PricingRuleRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CalculatorService {

    private static final String TYPE_DAY = "DAY";

    private final PricingRuleRepository pricingRuleRepository;

    // tính phí guest theo ngày (mỗi 24h tròn xuống lên 1 ngày, dưới 24h cũng tính 1 ngày)
    public int calculate(LocalDateTime entryTime, LocalDateTime exitTime) {
        PricingRuleEntity dayRule = pricingRuleRepository.findByType(TYPE_DAY)
                .orElseThrow(() -> new ServiceException("Chưa cấu hình giá theo đơn vị thời gian này", 500));
        long minutes = Math.max(1, Duration.between(entryTime, exitTime).toMinutes());
        int days = (int) Math.ceil(minutes / (60.0 * 24));
        return dayRule.getPrice() * days;
    }
}
