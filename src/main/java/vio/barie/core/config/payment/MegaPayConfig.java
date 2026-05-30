package vio.barie.core.config.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "megapay")
public class MegaPayConfig {
    private String merId;
    private String currency;
    private String payType;
    private String encodeKey;
    private String userLanguage;
    private String windowType;
    private String windowColor;
    private String callbackUrl;
    private String notifyUrl;
    private String reqDomain;
    private String cancelPw;
    private String sandboxUrl;
    private String productionUrl;
    private boolean isSandbox = true;
    private int qrExpireMinutes;

    public String getBaseUrl() {
        return isSandbox ? sandboxUrl : productionUrl;
    }

    public String getCreateLinkUrl() {
        return getBaseUrl() + "/pg_was/createlink.do";
    }

    public String getInquiryUrl() {
        return getBaseUrl() + "/pg_was/order/trxStatus.do";
    }

    public String getCancelUrl() {
        return getBaseUrl() + "/pg_was/cancel/paymentCancel.do";
    }

    public String getInquiryByInvoiceNoUrl() {
        return getBaseUrl() + "/pg_was/order/trxStatusInvoiceNo.do";
    }

    public String getEncryptKey() {
        return encodeKey.length() >= 24 ? encodeKey.substring(0, 24) : encodeKey;
    }

    public String getDecryptKey() {
        int len = encodeKey.length();
        return len >= 24 ? encodeKey.substring(len - 24) : encodeKey;
    }
}