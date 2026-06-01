package vio.barie.core.dto.payment;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePaymentResponse {
    private String resultCd;
    private String resultMsg;
    private String merId;
    private String invoiceNo;
    private String timeStamp;
    private BigDecimal amount;
    private String payType;
    private String payOption;
    private String linkExptime;
    private String paymentLink;
    private String qrCode;
    private String merchantToken;
}