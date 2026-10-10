package vio.barie.core.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Wrapper response từ GET /userapi/transactions/list.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SePayTransactionListResponse {
    private String status;
    @JsonAlias("meta")
    private Map<String, Object> metadata;
    @JsonAlias("transactions")
    private List<SePayTransaction> data;
}
