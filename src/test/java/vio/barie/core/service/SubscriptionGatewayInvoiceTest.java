package vio.barie.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.entity.PaymentEntity;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.sepay.SePayGateway;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SubscriptionGatewayInvoiceTest {
    @Test
    void sepayQrUsesCommittedInternalInvoice() {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setInvoiceNo("viotech_123456789");
        request.setAmount(new BigDecimal("10000"));
        CreatePaymentResponse response = gateway(mock(PaymentRepository.class), mock(VehicleSubscriptionPaymentService.class))
                .createPaymentLink(request);
        assertEquals("viotech_123456789", response.getInvoiceNo());
        assertTrue(response.getPaymentLink().contains("des=viotech_123456789"));
        assertEquals(new BigDecimal("10000"), response.getAmount());
    }

    @Test
    void subscriptionInvoiceUsesExistingPaymentIdentityInSeparateLegacyNamespace() {
        SePayGateway gateway = gateway(mock(PaymentRepository.class), mock(VehicleSubscriptionPaymentService.class));
        assertEquals("viotech_051", gateway.generateInvoiceNo(51L));
        assertEquals("viotech_052", gateway.generateInvoiceNo(52L));
        assertTrue(gateway.generateInvoiceNo().matches("viotech_[1-9][0-9]+"));
    }

    @Test
    void frontendCannotBindAnInternalInvoice() throws Exception {
        CreatePaymentRequest request = new ObjectMapper().readValue("{\"invoiceNo\":\"forged\",\"amount\":10000}",
                CreatePaymentRequest.class);
        assertNull(request.getInvoiceNo());
    }

    @Test
    void subscriptionCallbackRoutesOnceAndDoesNotExecuteLegacyTicketOrSessionActions() {
        PaymentRepository repository = mock(PaymentRepository.class);
        VehicleSubscriptionPaymentService service = mock(VehicleSubscriptionPaymentService.class);
        PaymentEntity payment = new PaymentEntity();
        payment.setCallbackType("SUBSCRIPTION");
        when(repository.findByInvoiceNo("viotech_123456789")).thenReturn(Optional.of(payment));
        gateway(repository, service).handleCallback("""
                {"id":"1234","accountNumber":"0123456789","content":"viotech_123456789 subscription",
                 "transferType":"in","transferAmount":10000,"transactionDate":"2026-10-11 10:30:00"}
                """);
        verify(service).applyVerifiedPayment(eq("viotech_123456789"), eq("SEPAY"), eq("1234"),
                eq(new BigDecimal("10000")), eq("VND"), any(), eq("OK"), isNull());
        verify(repository, never()).save(any());
    }

    @Test
    void incomingMoneyToAnotherBankAccountIsRejectedBeforeSettlement() {
        PaymentRepository repository = mock(PaymentRepository.class);
        VehicleSubscriptionPaymentService service = mock(VehicleSubscriptionPaymentService.class);
        PaymentEntity payment = new PaymentEntity();
        payment.setCallbackType("SUBSCRIPTION");
        when(repository.findByInvoiceNo("viotech_123456789")).thenReturn(Optional.of(payment));
        assertThrows(RuntimeException.class, () -> gateway(repository, service).handleCallback("""
                {"id":"1234","accountNumber":"wrong-account","code":"viotech_123456789",
                 "transferType":"in","transferAmount":10000}
                """));
        verifyNoInteractions(service);
    }

    @SuppressWarnings("unchecked")
    private SePayGateway gateway(PaymentRepository repository, VehicleSubscriptionPaymentService service) {
        SePayConfig config = new SePayConfig();
        config.setBankAccount("0123456789");
        config.setBankBrand("VCB");
        config.setQrBaseUrl("https://qr.invalid/image");
        config.setQrExpireMinutes(10);
        config.setInvoicePrefix("viotech_");
        ObjectProvider<VehicleSubscriptionPaymentService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        return new SePayGateway(repository, mock(TicketRepository.class), config,
                mock(ParkingMessageService.class), mock(CalculatorService.class), provider);
    }
}
