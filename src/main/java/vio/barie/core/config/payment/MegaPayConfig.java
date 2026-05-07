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
    private String endpoint;
    private String inquiryEndpoint;
    private String callbackUrl;
    private String notifyUrl;
    private String reqDomain;
}