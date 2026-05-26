package vio.barie.core.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Bản ghi giao dịch trả về từ
 * GET /userapi/transactions/list hoặc /userapi/transactions/details/{id}.
 *
 * Lưu ý: snake_case theo SePay (khác với webhook payload dùng camelCase).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SePayTransaction {
    /** ID giao dịch (kiểu string trong response, sẽ convert sang Long khi cần). */
    private String id;

    @JsonProperty("transaction_date")
    private String transactionDate;

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("sub_account")
    private String subAccount;

    @JsonProperty("amount_in")
    private BigDecimal amountIn;

    @JsonProperty("amount_out")
    private BigDecimal amountOut;

    private BigDecimal accumulated;

    @JsonProperty("transaction_content")
    private String transactionContent;

    @JsonProperty("reference_number")
    private String referenceNumber;

    @JsonProperty("bank_brand_name")
    private String bankBrandName;

    @JsonProperty("bank_account_id")
    private String bankAccountId;

    /**
     * Convert sang SePayWebhookPayload để dùng chung handler khi reconciliation
     * phát hiện webhook miss và cần "replay".
     */
    public SePayWebhookPayload toWebhookPayload() {
        SePayWebhookPayload w = new SePayWebhookPayload();
        try { w.setId(id != null ? Long.valueOf(id) : null); } catch (NumberFormatException ignored) {}
        w.setGateway(bankBrandName);
        w.setTransactionDate(transactionDate);
        w.setAccountNumber(accountNumber);
        w.setSubAccount(subAccount);
        w.setContent(transactionContent);
        w.setDescription(transactionContent);
        w.setReferenceCode(referenceNumber);
        w.setAccumulated(accumulated);
        // Xác định chiều tiền
        if (amountIn != null && amountIn.signum() > 0) {
            w.setTransferType("in");
            w.setTransferAmount(amountIn);
        } else if (amountOut != null && amountOut.signum() > 0) {
            w.setTransferType("out");
            w.setTransferAmount(amountOut);
        }
        return w;
    }
}
