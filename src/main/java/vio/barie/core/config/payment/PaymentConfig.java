package vio.barie.core.config.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.CalculatorService;
import vio.barie.core.service.VehicleSubscriptionService;
import vio.barie.core.service.ParkingPaymentService;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentGateway;
import vio.barie.core.service.payment.megapay.MegaPayGateway;
import vio.barie.core.service.payment.sepay.SePayGateway;

@Configuration
@RequiredArgsConstructor
public class PaymentConfig {
    @Value("${payment.method}")
    private String method;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final MegaPayConfig megaPayConfig;
    private final SePayConfig sePayConfig;
    private final ParkingMessageService ioTService;
    private final CalculatorService calculatorService;
    private final VehicleSubscriptionService vehicleSubscriptionService;
    private final ObjectProvider<ParkingPaymentService> parkingPaymentService;

    @Bean
    public PaymentGateway paymentGateway() {
        return switch (method) {
            case "megapay" -> {
                MegaPayGateway gateway = new MegaPayGateway(paymentRepository,ticketRepository,megaPayConfig,ioTService,vehicleSubscriptionService);
                gateway.setParkingPayments(parkingPaymentService);
                yield gateway;
            }
            case "sepay" -> {
                SePayGateway gateway = new SePayGateway(paymentRepository,ticketRepository,sePayConfig,ioTService,calculatorService,vehicleSubscriptionService);
                gateway.setParkingPayments(parkingPaymentService);
                yield gateway;
            }
            default -> throw new IllegalArgumentException("Unknown gateway: " + method);
        };
    }
}
