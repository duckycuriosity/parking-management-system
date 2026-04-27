package vio.barie.core.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import vio.barie.core.config.VmsWebSocketProperties;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class VmsWebSocketReconnectService {

    private final VmsWebSocketProperties properties;
    private final ObjectProvider<WebSocketConnectionManager> webSocketConnectionManagerProvider;

    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    public void requestReconnect(String reason) {
        if (!properties.isEnabled()) {
            return;
        }

        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        long delay = Math.max(properties.getReconnectDelayMs(), 500);
        log.info("Schedule VMS websocket reconnect in {} ms, reason: {}", delay, reason);

        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    try {
                        reconnectNow();
                    } finally {
                        reconnecting.set(false);
                    }
                });
    }

    private synchronized void reconnectNow() {
        if (!properties.isEnabled()) {
            return;
        }

        WebSocketConnectionManager webSocketConnectionManager = webSocketConnectionManagerProvider.getIfAvailable();
        if (webSocketConnectionManager == null) {
            log.warn("VMS websocket reconnect skipped because connection manager is not available");
            return;
        }

        try {
            if (webSocketConnectionManager.isRunning()) {
                webSocketConnectionManager.stop();
            }
        } catch (Exception ex) {
            log.warn("Error when stopping VMS websocket before reconnect: {}", ex.getMessage());
        }

        try {
            webSocketConnectionManager.start();
            log.info("VMS websocket reconnect triggered: {}", properties.getUrl());
        } catch (Exception ex) {
            log.warn("VMS websocket reconnect failed: {}", ex.getMessage());
            requestReconnect("reconnect failed");
        }
    }
}