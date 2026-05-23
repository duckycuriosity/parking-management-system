package vio.barie.core.config.vms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "vms.ws")
public class VmsWebSocketProperties {
    private boolean enabled = true;
    private String url;
    private long reconnectDelayMs = 3000;
    private String cookie;
    private String origin;
    private String userAgent = "Mozilla/5.0";
    private String accept = "*/*";
    private String connection = "keep-alive";
}