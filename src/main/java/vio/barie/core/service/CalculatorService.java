package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vio.barie.core.common.TicketType;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.entity.PricingRuleEntity;
import vio.barie.core.entity.TicketEntity;
import vio.barie.core.repository.PricingRuleRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CalculatorService {

    private final PricingRuleRepository pricingRuleRepository;

    // todo: thêm trường hợp là vé tháng bị hết hạn giữa chừng nữa
    // tính phí guest theo ngày (mỗi 24h tròn xuống lên 1 ngày, dưới 24h cũng tính 1 ngày)
    public BigDecimal calculate(LocalDateTime entryTime, LocalDateTime exitTime) {
        PricingRuleEntity rule = pricingRuleRepository.findByType(TicketType.DAY.name())
                .orElseThrow(() -> new ServiceException("Chưa cấu hình giá theo đơn vị thời gian này: " + TicketType.DAY.name(), 500));
        long minutes = Math.max(1, Duration.between(entryTime, exitTime).toMinutes());
        int days = (int) Math.ceil(minutes / (60.0 * 24));
        return BigDecimal.valueOf(rule.getPrice() * days);
    }

    public BigDecimal getTicketPrice(TicketEntity ticketEntity) {
        PricingRuleEntity rule = pricingRuleRepository.findByType(ticketEntity.getTicketType().name())
                .orElseThrow(() -> new ServiceException("Chưa cấu hình giá theo đơn vị thời gian này: " + ticketEntity.getTicketType(), 500));
        return BigDecimal.valueOf(rule.getPrice());
    }
}
