package vio.barie.core.config.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;
import vio.barie.core.service.payment.megapay.MegaPayGateway;
import vio.barie.core.service.payment.sepay.SePayGateway;

@Configuration
@ConfigurationProperties(prefix = "payment")
@RequiredArgsConstructor
public class PaymentConfig {
    @Value("${payment.method}")
    private String method;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final MegaPayConfig megaPayConfig;
    private final SePayConfig sePayConfig;
    private final ParkingMessageService ioTService;

    @Bean
    public PaymentGateway paymentGateway() {
        return switch (method) {
            case "megapay" -> new MegaPayGateway(paymentRepository,ticketRepository,megaPayConfig,ioTService);
            case "sepay"   -> new SePayGateway(paymentRepository,ticketRepository,sePayConfig,ioTService);
            default -> throw new IllegalArgumentException("Unknown gateway: " + method);
        };
    }
}
