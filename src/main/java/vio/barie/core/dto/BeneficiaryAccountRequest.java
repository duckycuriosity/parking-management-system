package vio.barie.core.dto;

import lombok.Data;

@Data
public class BeneficiaryAccountRequest {
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountHolderName;
    private String note;
    private Long buildingId;
    private Long parkingLotId;
}
