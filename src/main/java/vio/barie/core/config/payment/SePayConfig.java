package vio.barie.core.config.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sepay")
public class SePayConfig {

    private String apiBaseUrl;

    /** Bearer token để backend chủ động gọi SePay API (inquiry, reconciliation, stats). */
    private String apiToken;

    /** Optional full endpoint for subscription inquiries; does not change legacy inquiry routing. */
    private String subscriptionTransactionsUrl;

    /** API key SePay đính kèm trong header "Authorization: Apikey <KEY>" khi gọi webhook. */
    private String webhookApiKey;

    /** Số tài khoản ngân hàng thụ hưởng - embed vào VietQR. */
    private String bankAccount;

    /** Tên brand ngân hàng (Vietcombank, VPBank, BIDV…). */
    private String bankBrand;

    /** Endpoint sinh ảnh VietQR. */
    private String qrBaseUrl;

    /** Số phút coi như QR hết hạn (do mình tự quản, SePay không expire QR). */
    private int qrExpireMinutes;

    /** Tiền tố invoiceNo - dùng cả khi sinh và khi parse từ webhook `content`. */
    private String invoicePrefix;

    /** Cron polling reconciliation (ms). Default 5 phút. */
    private long reconciliationIntervalMs = 5L * 60L * 1000L;

    public String getTransactionsListUrl()   { return apiBaseUrl + "/transactions"; }
    public String getSubscriptionTransactionsListUrl() {
        return subscriptionTransactionsUrl == null || subscriptionTransactionsUrl.isBlank()
                ? getTransactionsListUrl() : subscriptionTransactionsUrl;
    }
}
