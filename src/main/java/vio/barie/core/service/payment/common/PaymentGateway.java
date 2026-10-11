package vio.barie.core.service.payment.common;

import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.payment.PaymentInquiryResponse;

public interface PaymentGateway {
    String generateInvoiceNo(Long paymentId);
    default String generateInvoiceNo() { return generateInvoiceNo(null); }
    String gatewayName();
    default boolean createsLinkRemotely() { return true; }
    CreatePaymentResponse createPaymentLink(CreatePaymentRequest req);
    void handleCallback(String body);
    PaymentInquiryResponse inquiryByInvoiceNo(String invoiceNo);
}
