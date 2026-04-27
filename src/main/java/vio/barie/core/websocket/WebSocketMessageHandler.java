package vio.barie.core.websocket;

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

    private final ObjectMapper objectMapper;
    private final VmsWebSocketReconnectService reconnectService;
    private final vio.barie.core.service.VmsService vmsService;

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

        try {
            vmsService.fetchAndStoreLatestEventIfRecent().ifPresent(
                    saved -> log.info("Saved VMS event id={} from websocket trigger", saved.getVmsEventId()));
        } catch (Exception ex) {
            log.warn("Failed to fetch/store latest VMS event from websocket trigger: {}", ex.getMessage());
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
