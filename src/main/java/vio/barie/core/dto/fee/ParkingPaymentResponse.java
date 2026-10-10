package vio.barie.core.dto.fee;

import vio.barie.core.dto.payment.CreatePaymentResponse;

public record ParkingPaymentResponse(Long attemptId, Long calculationId, Long paymentId, String invoiceNo,
        long amountVnd, String currency, String status, String applicationStatus, String applicationReason,
        CreatePaymentResponse gatewayLink) { }
