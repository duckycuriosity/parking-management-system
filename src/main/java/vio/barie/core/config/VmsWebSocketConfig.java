package vio.barie.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import vio.barie.core.websocket.WebSocketMessageHandler;

@Configuration
public class VmsWebSocketConfig {

    @Bean
    public WebSocketConnectionManager vmsWebSocketConnectionManager(
            VmsWebSocketProperties properties,
            WebSocketMessageHandler webSocketMessageHandler) {

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketConnectionManager connectionManager = new WebSocketConnectionManager(client, webSocketMessageHandler,
                properties.getUrl());

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("User-Agent", properties.getUserAgent());
        headers.add("Accept", properties.getAccept());
        headers.add("Connection", properties.getConnection());
        if (properties.getCookie() != null && !properties.getCookie().isBlank()) {
            headers.add("Cookie", properties.getCookie());
        }
        if (properties.getOrigin() != null && !properties.getOrigin().isBlank()) {
            headers.add("Origin", properties.getOrigin());
        }

        connectionManager.setHeaders(headers);
        connectionManager.setAutoStartup(false);
        return connectionManager;
    }
}