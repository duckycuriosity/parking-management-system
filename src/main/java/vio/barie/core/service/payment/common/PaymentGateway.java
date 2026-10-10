package vio.barie.core.service.payment.common;

import vio.barie.core.dto.payment.CreatePaymentRequest;
import vio.barie.core.dto.payment.CreatePaymentResponse;
import vio.barie.core.dto.payment.PaymentInquiryResponse;

public interface PaymentGateway {
    CreatePaymentResponse createPaymentLink(CreatePaymentRequest req);
    /** Internal intent creation: the invoice is already committed and must never be replaced. */
    CreatePaymentResponse createPaymentLink(CreatePaymentRequest req, String invoiceNo);
    void handleCallback(String body);
    PaymentInquiryResponse inquiryByInvoiceNo(String invoiceNo);
}
