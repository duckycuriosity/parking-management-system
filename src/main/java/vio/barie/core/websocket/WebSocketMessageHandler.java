package vio.barie.core.websocket;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketMessageHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private VmsWebSocketReconnectService reconnectService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("VMS websocket connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws IOException {
        String payload = message.getPayload();

        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            log.info("VMS websocket message received: {}", jsonNode.toString());
        } catch (IOException ex) {
            log.info("VMS websocket raw message received: {}", payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("VMS websocket disconnected: sessionId={}, status={}", session.getId(), status);
        reconnectService.requestReconnect("connection closed");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = session != null ? session.getId() : "unknown";
        log.warn("VMS websocket transport error: sessionId={}, message={}", sessionId, exception.getMessage());
        reconnectService.requestReconnect("transport error");
    }

}
