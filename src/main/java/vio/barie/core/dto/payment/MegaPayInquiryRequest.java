package vio.barie.core.dto.payment;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MegaPayInquiryRequest {
    private String invoiceNo;
    private String merId;
    private String merchantToken;
    private String timeStamp;
}
