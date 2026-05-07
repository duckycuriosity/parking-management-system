package vio.barie.core.dto.payment;

import lombok.Data;

@Data
public class CreatePaymentResponse {
    private String resultCd;
    private String resultMsg;
    private String merId;
    private String invoiceNo;
    private String timeStamp;
    private String amount;
    private String payType;
    private String payOption;
    private String linkExptime;
    private String paymentLink;
    private String qrCode;
    private String merchantToken;

    public boolean isSuccess() {
        return "00_000".equals(resultCd);
    }
}
