package vio.barie.core.service.payment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.config.payment.MegaPayConfig;
import vio.barie.core.config.payment.SePayConfig;
import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.repository.PaymentRepository;
import vio.barie.core.repository.TicketRepository;
import vio.barie.core.service.CalculatorService;
import vio.barie.core.service.VehicleSubscriptionService;
import vio.barie.core.service.mqtt.ParkingMessageService;
import vio.barie.core.service.payment.common.PaymentLinkRejectedException;
import vio.barie.core.service.payment.megapay.MegaPayGateway;
import vio.barie.core.service.payment.sepay.SePayGateway;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Gateway request identity is backend-reserved; legacy one-argument callers remain supported. */
class SubscriptionGatewayInvoiceTest {
    @Test
    void sepayQrContainsTheCommittedInvoiceAndFrozenAmount() {
        SePayGateway gateway = sepay();
        CreatePaymentResponse response = gateway.createPaymentLink(request(), "SUB123456");
        assertEquals("SUB123456", response.getInvoiceNo());
        assertEquals(BigDecimal.valueOf(50000), response.getAmount());
        assertTrue(response.getPaymentLink().contains("des=SUB123456"));
        assertTrue(response.getPaymentLink().contains("amount=50000"));
    }

    @Test
    void legacySepayCallStillAllocatesItsOwnInvoice() {
        assertTrue(sepay().createPaymentLink(request()).getInvoiceNo().matches("SUB[0-9]+"));
    }

    @Test
    void megapaySignsAndSendsTheAlreadyCommittedInvoice() {
        MegaPayGateway gateway = megapay();
        CreatePaymentResponse gatewayResponse = new CreatePaymentResponse();
        gatewayResponse.setResultCd("00_000");
        gatewayResponse.setInvoiceNo("viotech_123456");
        gatewayResponse.setPaymentLink("encrypted");
        doReturn(gatewayResponse).when(gateway).createPaymentLink(anyMap());
        doReturn("https://example.test/pay").when(gateway).decodePaymentLink("encrypted");
        assertEquals("viotech_123456", gateway.createPaymentLink(request(), "viotech_123456").getInvoiceNo());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(gateway).createPaymentLink(params.capture());
        assertEquals("viotech_123456", params.getValue().get("invoiceNo"));
        assertEquals("50000", params.getValue().get("amount"));
        assertNotNull(params.getValue().get("merchantToken"));
    }

    @Test
    void megapayNonSuccessCannotBeClassifiedAsSafeToCreateAnotherInvoice() {
        MegaPayGateway gateway = megapay();
        CreatePaymentResponse response = new CreatePaymentResponse();
        response.setResultCd("99");
        doReturn(response).when(gateway).createPaymentLink(anyMap());
        ServiceException failure = assertThrows(ServiceException.class,
                () -> gateway.createPaymentLink(request(), "viotech_123456"));
        assertFalse(failure instanceof PaymentLinkRejectedException);
        verify(gateway, never()).decodePaymentLink(any());
    }

    @Test
    void invalidLocalConfigurationFailsBeforeGatewayIo() {
        SePayConfig config = new SePayConfig();
        SePayGateway gateway = new SePayGateway(mock(PaymentRepository.class), mock(TicketRepository.class), config,
                mock(ParkingMessageService.class), mock(CalculatorService.class), mock(VehicleSubscriptionService.class));
        assertThrows(PaymentLinkRejectedException.class, () -> gateway.createPaymentLink(request(), "SUB123456"));
    }

    private SePayGateway sepay() {
        SePayConfig config = new SePayConfig();
        config.setInvoicePrefix("SUB");
        config.setQrExpireMinutes(15);
        config.setBankAccount("123456789");
        config.setBankBrand("Vietcombank");
        config.setQrBaseUrl("https://example.test/qr");
        return new SePayGateway(mock(PaymentRepository.class), mock(TicketRepository.class), config,
                mock(ParkingMessageService.class), mock(CalculatorService.class), mock(VehicleSubscriptionService.class));
    }

    private MegaPayGateway megapay() {
        MegaPayConfig config = new MegaPayConfig();
        config.setMerId("test_merchant");
        config.setEncodeKey("123456789012345678901234");
        config.setQrExpireMinutes(15);
        config.setPayType("QR");
        return spy(new MegaPayGateway(mock(PaymentRepository.class), mock(TicketRepository.class), config,
                mock(ParkingMessageService.class), mock(VehicleSubscriptionService.class)));
    }

    private CreatePaymentRequest request() {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setAmount(BigDecimal.valueOf(50000));
        request.setGoodsName("Parking subscription");
        request.setDescription("Subscription 1");
        return request;
    }
}
