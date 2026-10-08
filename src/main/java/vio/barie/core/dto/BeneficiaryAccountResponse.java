package vio.barie.core.dto;

import lombok.Data;

@Data
public class BeneficiaryAccountResponse {
    private Long id;
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountHolderName;
    private String status;
    private String note;
    private Long buildingId;
    private Long parkingLotId;
    // Hàm này dùng để hiện 6 số cuối và ẩn toàn bộ số đầu
    public String getMaskedAccountNumber() {
        if (accountNumber != null && accountNumber.length() > 4) {
            return "******" + accountNumber.substring(accountNumber.length() - 6);
        }
        return accountNumber;
    }
}
