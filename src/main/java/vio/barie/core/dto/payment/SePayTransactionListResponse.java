package vio.barie.core.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Wrapper response từ GET /userapi/transactions/list.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SePayTransactionListResponse {
    private Integer status;
    private String error;
    private Map<String, Object> messages;
    private List<SePayTransaction> transactions;
}
