package vio.barie.core.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SePayTransaction {
    @JsonProperty("id")
    private String id;

    @JsonProperty("transaction_date")
    private String transactionDate;

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("va")
    private String va;

    @JsonProperty("transfer_type")
    private String transferType;

    @JsonProperty("amount_in")
    private BigDecimal amountIn;

    @JsonProperty("amount_out")
    private BigDecimal amountOut;

    @JsonProperty("accumulated")
    private BigDecimal accumulated;

    @JsonProperty("transaction_content")
    private String transactionContent;

    @JsonProperty("reference_number")
    private String referenceNumber;

    @JsonProperty("code")
    private String code;

    @JsonProperty("bank_brand_name")
    private String bankBrandName;

    @JsonProperty("bank_account_id")
    private String bankAccountId;

    @JsonProperty("va_id")
    private String vaId;

    @JsonProperty("webhook_success")
    private Integer webhookSuccess;
}
