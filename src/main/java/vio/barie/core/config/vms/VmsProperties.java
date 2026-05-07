package vio.barie.core.config.vms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vms.core")
public class VmsProperties {
    private String baseUrl;
    private String loginPath = "/LogIn";
    private String dataPath = "/Data";
    private String username;
    private String password;
    private String cookie;
    private String origin;
    private String loginReferer;
    private String dataReferer;
    private String userAgent = "Mozilla/5.0";
    private String accept = "*/*";
    private String acceptLanguage = "vi-VN,vi;q=0.9,fr-FR;q=0.8,fr;q=0.7,en-US;q=0.6,en;q=0.5";
    private String connection = "keep-alive";
    private String tokenName = "VMS_TOKEN";
    private String dataType = "sensor-face-unknown-camera";
    private String dataOrder = "desc";
    private Integer dataIsConfirmed = 1;
    private Integer dataCount = 1; // for get latest event
}