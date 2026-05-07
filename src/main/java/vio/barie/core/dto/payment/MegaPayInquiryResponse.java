package vio.barie.core.dto.payment;

import lombok.Data;

@Data
public class MegaPayInquiryResponse {
    private String resultCd;
    private MegaPayResponse data;
}
