package vio.barie.core.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * {
 *   "id": 92704,
 *   "gateway": "Vietcombank",
 *   "transactionDate": "2024-07-02 11:08:33",
 *   "accountNumber": "1017588888",
 *   "subAccount": "",
 *   "code": "SEVN63DC8E5C",
 *   "content": "viotech_1737..  Phi gui xe",
 *   "transferType": "in",
 *   "description": "NGUYEN VAN A chuyen tien",
 *   "transferAmount": 5000000,
 *   "accumulated": 105000000,
 *   "referenceCode": "FT24012345678"
 * }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SePayWebhookPayload {
    /** ID giao dịch duy nhất = cột sepay_tx_id */
    private Long id;
    /** Tên ngân hàng: Vietcombank, VPBank... */
    private String gateway;
    /** yyyy-MM-dd HH:mm:ss */
    private String transactionDate;
    private String accountNumber;
    private String subAccount;
    /** Mã thanh toán do SePay sinh, có thể null. */
    private String code;
    /** Nội dung CK đầy đủ - chứa invoiceNo nếu QR sinh đúng. */
    private String content;
    /** "in" = tiền vào, "out" = tiền ra. */
    private String transferType;
    private String description;
    private BigDecimal transferAmount;
    private BigDecimal accumulated;
    private String referenceCode;
}
