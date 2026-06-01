package vio.barie.core.dto.payment;

import cn.hutool.json.JSONObject;
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
    private String status;
    private JSONObject metadata;
    private List<SePayTransaction> data;
}
