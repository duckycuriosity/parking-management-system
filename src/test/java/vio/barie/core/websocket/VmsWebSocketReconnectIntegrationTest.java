package vio.barie.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import vio.barie.core.config.VmsWebSocketConfig;
import vio.barie.core.config.VmsWebSocketProperties;
import vio.barie.core.service.VmsService;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(VmsWebSocketReconnectIntegrationTest.TestConfig.class)
class VmsWebSocketReconnectIntegrationTest {

    private static final AtomicInteger OPEN_COUNT = new AtomicInteger(0);
    private static MockWebServer mockWebServer;

    @Autowired
    private VmsWebSocketReconnectService reconnectService;

    @Autowired
    private WebSocketConnectionManager webSocketConnectionManager;

    @AfterAll
    static void afterAll() {
        if (mockWebServer != null) {
            try {
                mockWebServer.shutdown();
            } catch (Exception ignored) {
                // Ignore shutdown issues during test teardown.
            }
        }
    }

    @AfterEach
    void afterEach() {
        webSocketConnectionManager.stop();
        OPEN_COUNT.set(0);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("vms.ws.enabled", () -> "true");
        registry.add("vms.ws.reconnect-delay-ms", () -> "100");
        registry.add("vms.ws.url", () -> getOrStartServer().url("/ws/Notify").toString().replace("http://", "ws://"));
    }

    @Test
    void shouldReconnectWhenServerDisconnectsClient() throws Exception {
        mockWebServer.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                OPEN_COUNT.incrementAndGet();
                webSocket.close(1001, "force reconnect");
            }
        }));

        mockWebServer.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                OPEN_COUNT.incrementAndGet();
            }
        }));

        reconnectService.requestReconnect("integration test start");

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(8);
        while (System.currentTimeMillis() < deadline && OPEN_COUNT.get() < 2) {
            Thread.sleep(100);
        }

        assertTrue(OPEN_COUNT.get() >= 2,
                "Expected websocket to reconnect after disconnect, openCount=" + OPEN_COUNT.get());
    }

    @Configuration
    @EnableConfigurationProperties(VmsWebSocketProperties.class)
    @Import({ VmsWebSocketConfig.class, WebSocketMessageHandler.class, VmsWebSocketReconnectService.class })
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        VmsService vmsService() {
            return mock(VmsService.class);
        }
    }

    private static synchronized MockWebServer getOrStartServer() {
        if (mockWebServer == null) {
            mockWebServer = new MockWebServer();
            try {
                mockWebServer.start();
            } catch (Exception ex) {
                throw new RuntimeException("Unable to start mock websocket server", ex);
            }
        }
        return mockWebServer;
    }
}